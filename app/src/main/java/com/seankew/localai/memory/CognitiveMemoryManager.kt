package com.seankew.localai.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

/** 反思所需的最小 LLM 能力。生产环境由 MnnLlm.complete 适配，测试里用假实现。 */
fun interface ReflectionLlm {
    /** 返回模型原始文本；失败 / 被打断返回 null。取消时必须抛 CancellationException。 */
    suspend fun complete(system: String, user: String, maxNewTokens: Int): String?
}

data class ComposeResult(val systemPrompt: String?, val used: List<MemoryRecord>)

enum class UpsertKind { ADDED, REINFORCED, MERGED }

data class UpsertResult(val kind: UpsertKind, val record: MemoryRecord)

data class ReflectionOutcome(val results: List<UpsertResult>, val rejected: List<String>) {
    val added: List<MemoryRecord> get() = results.filter { it.kind != UpsertKind.REINFORCED }.map { it.record }
    companion object { val EMPTY = ReflectionOutcome(emptyList(), emptyList()) }
}

sealed interface ExplicitResult {
    data class Saved(val result: UpsertResult) : ExplicitResult
    data class Rejected(val reason: String) : ExplicitResult
}

/**
 * 认知记忆系统的编排者：
 * 1. [composeSystemPrompt]：每轮生成前检索相关记忆并拼进系统提示词（本地 RAG）；
 * 2. [reflect]：对话结束后让模型静默提取值得记住的信息，校验、去重后写库（自学习）；
 * 3. [rememberExplicit]：用户明确说"记住……"时立即写入。
 *
 * 任何存储层的异常都不会向上传播影响聊天——记忆是增强，不是依赖。
 */
class CognitiveMemoryManager(
    private val store: MemoryStore,
    private val retriever: MemoryRetriever = MemoryRetriever(),
    private val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    data class Config(
        val maxMemories: Int = 500,
        /** 与已有记忆的近重复阈值（Jaccard）。 */
        val nearDuplicate: Double = 0.6,
        val candidateRecent: Int = 24,
        val candidatePerTerm: Int = 12,
        val maxSearchTerms: Int = 6,
        val reflectionMaxTokens: Int = 160,
        /** 参与近重复比较的最近记录数。 */
        val dedupeWindow: Int = 200,
    )

    // ------------------------------------------------------------------ 1. 检索 + 注入
    suspend fun composeSystemPrompt(base: String?, userText: String): ComposeResult {
        return try {
            val now = clock()
            val candidates = LinkedHashMap<Long, MemoryRecord>()
            store.recent(config.candidateRecent).forEach { candidates[it.id] = it }
            store.pinned().forEach { candidates[it.id] = it }
            // 关键词候选：优先较长 / 强信号的词，避免大量噪声 bigram 的 LIKE 查询
            val terms = MemoryText.terms(userText, MemoryText.MAX_QUERY_TERMS)
                .sortedWith(compareByDescending<String> { MemoryText.isStrongTerm(it) }.thenByDescending { it.length })
                .take(config.maxSearchTerms)
            for (term in terms) store.search(term, config.candidatePerTerm).forEach { candidates[it.id] = it }

            val picked = retriever.select(userText, candidates.values, now).map { it.record }
            if (picked.isNotEmpty()) store.markUsed(picked.map { it.id }, now)
            ComposeResult(MemoryPrompt.compose(base, picked), picked)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("记忆检索失败，已跳过：${e.message ?: e.javaClass.simpleName}")
            ComposeResult(base, emptyList())
        }
    }

    // ------------------------------------------------------------------ 2. 显式记忆
    /** 用户消息不是"记住……"指令时返回 null。 */
    suspend fun rememberExplicit(userText: String): ExplicitResult? {
        val content = ExplicitMemory.extract(userText) ?: return null
        ReflectionParser.validateContent(content)?.let { return ExplicitResult.Rejected(it) }
        return try {
            val candidate = MemoryCandidate(MemoryKind.guess(content), 4, content)
            ExplicitResult.Saved(upsert(candidate, MemorySource.USER_EXPLICIT))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("写入记忆失败：${e.message ?: e.javaClass.simpleName}")
            ExplicitResult.Rejected("存储失败")
        }
    }

    // ------------------------------------------------------------------ 3. 自动反思
    suspend fun reflect(llm: ReflectionLlm, userText: String, answer: String): ReflectionOutcome {
        val raw = try {
            llm.complete(ReflectionPrompt.SYSTEM, ReflectionPrompt.userMessage(userText, answer), config.reflectionMaxTokens)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("反思失败：${e.message ?: e.javaClass.simpleName}")
            null
        } ?: return ReflectionOutcome.EMPTY

        val parsed = ReflectionParser.parse(raw)
        if (parsed.rejected.isNotEmpty()) log("反思丢弃 ${parsed.rejected.size} 条：${parsed.rejected.joinToString("；")}")
        if (parsed.candidates.isEmpty()) return ReflectionOutcome(emptyList(), parsed.rejected)

        val results = ArrayList<UpsertResult>()
        try {
            for (candidate in parsed.candidates) results += upsert(candidate, MemorySource.AUTO_REFLECTION)
            if (store.count() > config.maxMemories) store.pruneLowestValue(config.maxMemories)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("写入记忆失败：${e.message ?: e.javaClass.simpleName}")
        }
        return ReflectionOutcome(results, parsed.rejected)
    }

    // ------------------------------------------------------------------ 去重写入
    /**
     * 精确重复 → 强化（提升重要度、刷新时间）；
     * 与同类型近重复 → 合并（保留信息量更大的那句）；否则新增。
     */
    suspend fun upsert(candidate: MemoryCandidate, source: MemorySource): UpsertResult {
        val now = clock()
        val fingerprint = MemoryText.fingerprint(candidate.content)

        store.byFingerprint(fingerprint)?.let { return reinforce(it, candidate, now) }

        val newTerms = MemoryText.terms(candidate.content)
        val near = store.recent(config.dedupeWindow)
            .filter { it.kind == candidate.kind }
            .map { it to MemoryText.jaccard(newTerms, MemoryText.terms(it.content)) }
            .filter { it.second >= config.nearDuplicate }
            .maxByOrNull { it.second }
            ?.first
        if (near != null) {
            val keepNew = candidate.content.length > near.content.length
            val merged = near.copy(
                content = if (keepNew) candidate.content else near.content,
                fingerprint = if (keepNew) fingerprint else near.fingerprint,
                importance = maxOf(near.importance, candidate.importance),
                updatedAt = now,
            )
            store.update(merged)
            return UpsertResult(UpsertKind.MERGED, merged)
        }

        val record = MemoryRecord(
            kind = candidate.kind,
            content = candidate.content,
            importance = candidate.importance.coerceIn(1, 5),
            source = source,
            createdAt = now,
            updatedAt = now,
            fingerprint = fingerprint,
        )
        val id = store.insert(record)
        if (id < 0) {   // 并发写入同一指纹：按已有记录强化
            store.byFingerprint(fingerprint)?.let { return reinforce(it, candidate, now) }
        }
        return UpsertResult(UpsertKind.ADDED, record.copy(id = id))
    }

    private suspend fun reinforce(existing: MemoryRecord, candidate: MemoryCandidate, now: Long): UpsertResult {
        val updated = existing.copy(importance = maxOf(existing.importance, candidate.importance), updatedAt = now)
        store.update(updated)
        return UpsertResult(UpsertKind.REINFORCED, updated)
    }

    // ------------------------------------------------------------------ 管理
    private val _storeError = MutableStateFlow<String?>(null)

    /**
     * 记忆库当前不可用的原因（数据库损坏、缺少迁移、磁盘错误等）；正常时为 null。
     * 只反映 [observeAll] 这条观察流的状态：界面一启动就会订阅它，所以最先暴露问题；收到下一次成功数据后自动清除。
     */
    val storeError: StateFlow<String?> = _storeError.asStateFlow()

    /**
     * 观察全部记忆。**这条流永远不会抛异常**：数据库打不开 / 缺少迁移 / 中途出错时，
     * 记录原因到 [storeError]，发出一个空列表后正常结束 —— 否则异常会一路冒泡成应用启动即崩溃。
     * 取消（界面离开）不算错误，照常传播。下次重新订阅时会重试。
     */
    fun observeAll(): Flow<List<MemoryRecord>> =
        flow { emitAll(store.observeAll()) }   // 在 flow 内部取 store.observeAll()，连同步抛出的异常也一并兜住
            .onEach { _storeError.value = null }
            .catch { error ->
                val reason = error.message ?: error.javaClass.simpleName
                _storeError.value = reason
                log("记忆库不可用：$reason")
                emit(emptyList())
            }
    suspend fun delete(id: Long) = store.delete(id)
    suspend fun clear() = store.clear()
    suspend fun setPinned(id: Long, pinned: Boolean) = store.setPinned(id, pinned, clock())
    suspend fun count(): Int = store.count()
}
