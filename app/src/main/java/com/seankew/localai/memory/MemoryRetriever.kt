package com.seankew.localai.memory

import kotlin.math.exp

data class RetrievalConfig(
    /** 最多注入几条。 */
    val maxItems: Int = 5,
    /** 记忆块（所有条目行）的字符预算——每轮都要重新预填充，必须克制。 */
    val charBudget: Int = 480,
    /** 词法相似度下限，低于它视为无关。 */
    val minLexical: Double = 0.12,
    /** 至少命中几个检索词（强词，如较长的英文词，命中 1 个即可）。 */
    val minSharedTerms: Int = 2,
    /** "常驻"名额：置顶记忆 + 高重要度偏好，不论本轮话题是否相关都注入。 */
    val maxStanding: Int = 3,
    val standingMinImportance: Int = 4,
)

data class ScoredMemory(val record: MemoryRecord, val score: Double, val standing: Boolean)

/**
 * 本地"RAG"的检索与排序（纯 Kotlin）：对候选记忆打分并在字符预算内挑选。
 *
 * - 常驻：置顶记忆、重要度 >= 4 的偏好（如"回答要简洁"）总是相关。
 * - 话题：词法相似度过线才注入；没有任何相关记忆时返回空，不污染提示词。
 */
class MemoryRetriever(private val config: RetrievalConfig = RetrievalConfig()) {

    fun select(query: String, candidates: Collection<MemoryRecord>, now: Long): List<ScoredMemory> {
        if (candidates.isEmpty()) return emptyList()
        val distinct = candidates.distinctBy { it.id }
        val queryTerms = MemoryText.terms(query, MemoryText.MAX_QUERY_TERMS)

        // 1) 常驻名额
        val standing = distinct
            .filter { it.pinned || (it.kind == MemoryKind.PREFERENCE && it.importance >= config.standingMinImportance) }
            .sortedWith(compareByDescending<MemoryRecord> { it.pinned }.thenByDescending { it.importance }.thenByDescending { it.updatedAt })
            .take(config.maxStanding)
            .map { ScoredMemory(it, 1.0 + it.importance / 10.0, standing = true) }

        // 2) 话题相关
        val standingIds = standing.mapTo(HashSet()) { it.record.id }
        val topical = distinct
            .asSequence()
            .filter { it.id !in standingIds }
            .mapNotNull { score(it, queryTerms, now) }
            .sortedByDescending { it.score }
            .toList()

        // 3) 在条数与字符预算内装填：常驻在前，其余按分数
        val picked = ArrayList<ScoredMemory>()
        var used = 0
        for (candidate in standing + topical) {
            if (picked.size >= config.maxItems) break
            val cost = MemoryPrompt.line(candidate.record).length + 1
            if (used + cost > config.charBudget) continue   // 放不下就试下一条更短的
            picked += candidate
            used += cost
        }
        return picked
    }

    private fun score(record: MemoryRecord, queryTerms: Set<String>, now: Long): ScoredMemory? {
        if (queryTerms.isEmpty()) return null
        val memoryTerms = MemoryText.terms(record.content)
        val shared = queryTerms.filter { it in memoryTerms }
        if (shared.isEmpty()) return null
        val strong = shared.any { MemoryText.isStrongTerm(it) }
        if (shared.size < config.minSharedTerms && !strong) return null

        val lexical = MemoryText.cosine(queryTerms, memoryTerms)
        if (lexical < config.minLexical && !strong) return null

        val ageDays = ((now - maxOf(record.updatedAt, record.lastUsedAt)).coerceAtLeast(0L)) / DAY_MS.toDouble()
        val recency = exp(-ageDays / 45.0)
        val importance = record.importance.coerceIn(1, 5) / 5.0
        val score = lexical.coerceAtLeast(if (strong) 0.2 else 0.0) * 0.6 + importance * 0.25 + recency * 0.15
        return ScoredMemory(record, score, standing = false)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
