package com.seankew.localai

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.seankew.localai.memory.ArchivistDB
import com.seankew.localai.memory.CognitiveMemoryManager
import com.seankew.localai.memory.ExplicitResult
import com.seankew.localai.memory.MemoryRecord
import com.seankew.localai.memory.ReflectionGate
import com.seankew.localai.memory.ReflectionLlm
import com.seankew.localai.memory.RoomMemoryStore
import com.seankew.localai.memory.UpsertKind
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

// ============================================================================
//  UI 层看到的数据类型
// ============================================================================

enum class ChatRole { USER, ASSISTANT }

/**
 * 一条聊天消息。ViewModel 只会对流式回答做"追加文本"，UI 可以据此做增量渲染。
 * [isStreaming] 为 true 表示这条回答仍在生成中。
 */
data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String = "",
    val isStreaming: Boolean = false,
)

/** 模型生命周期阶段；UI 的按钮可用性、状态灯都由它推导。 */
enum class ModelPhase { UNLOADED, IMPORTING, INITIALIZING, READY, GENERATING, RELEASING }

data class ModelFileEntry(val name: String, val present: Boolean, val sizeBytes: Long)

data class ModelFilesState(val dirPath: String, val files: List<ModelFileEntry>) {
    val missing: List<String> get() = files.filter { !it.present }.map { it.name }
    val complete: Boolean get() = missing.isEmpty()
}

// ============================================================================
//  ChatViewModel
// ============================================================================

/**
 * 持有 [MnnLlm]、聊天记录与模型状态。
 *
 * - 屏幕旋转 / 深色模式切换只会重建 Activity，不会重建 ViewModel，因此模型与对话都不受影响。
 * - 流式生成跑在 [viewModelScope] 里：Activity 重建期间生成不会中断，新 Activity 订阅后直接看到最新文本。
 * - 只有 Activity 真正结束（用户退出 / finish）时 [onCleared] 才会被调用，此时释放 native 资源。
 *
 * 线程约定：所有公开方法都应在主线程调用（viewModelScope 默认即 Dispatchers.Main.immediate）。
 * MnnLlm 内部自己保证 native 调用在单线程上串行。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val MODEL_DIR_NAME = "qwen2.5-1.5b-mnn"
        const val MAX_EVENT_LINES = 16
        const val INITIAL_EVENT = "先导入模型文件，再初始化。"

        const val PREFS_NAME = "localai_prefs"
        const val KEY_MEMORY_ENABLED = "memory_enabled"
        const val KEY_LEARNING_ENABLED = "learning_enabled"

        /** 回答结束后等多久再开始后台反思：给界面收尾、也给用户紧接着追问留出时间（追问会立刻打断反思）。 */
        const val REFLECTION_DELAY_MS = 2_000L

        val REQUIRED_MODEL_FILES = listOf(
            "config.json",
            "llm_config.json",
            "llm.mnn",
            "llm.mnn.weight",
            "tokenizer.txt",
        )

        // 对本运行时可选，但存在时会一并复制
        val OPTIONAL_MODEL_FILES = listOf("llm.mnn.json", "embeddings_bf16.bin")
    }

    private val app: Application = application

    // ---------------------------------------------------------------- 对外状态
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _phase = MutableStateFlow(ModelPhase.UNLOADED)
    val phase: StateFlow<ModelPhase> = _phase.asStateFlow()

    /** 最近 [MAX_EVENT_LINES] 条运行日志。 */
    private val _events = MutableStateFlow(listOf(INITIAL_EVENT))
    val events: StateFlow<List<String>> = _events.asStateFlow()

    private val _modelFiles = MutableStateFlow(scanModelFiles())
    val modelFiles: StateFlow<ModelFilesState> = _modelFiles.asStateFlow()

    /**
     * 一次性提示（Toast）。用 Channel 而不是 SharedFlow：
     * Activity 重建的空窗期里产生的提示会被缓冲，不会丢。
     */
    private val toastChannel = Channel<String>(Channel.BUFFERED)
    val toasts: Flow<String> = toastChannel.receiveAsFlow()

    // ---------------------------------------------------------------- 内部状态（仅主线程）
    private var llm: MnnLlm? = null
    private var importing = false
    private var generationJob: Job? = null
    private var nextMessageId = 0L

    // ---------------------------------------------------------------- 认知记忆（长时记忆 + 本地 RAG + 自学习）
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 延迟创建：Room 数据库在第一次真正查询时才会在后台线程打开。 */
    private val memory: CognitiveMemoryManager by lazy {
        CognitiveMemoryManager(
            store = RoomMemoryStore(ArchivistDB.get(app).memoryDao()),
            log = { logEvent("🧠 $it") },
        )
    }

    private val _memoryEnabled = MutableStateFlow(prefs.getBoolean(KEY_MEMORY_ENABLED, true))
    /** 总开关：关闭后既不检索注入，也不学习，行为与没有记忆系统时完全一致。 */
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    private val _learningEnabled = MutableStateFlow(prefs.getBoolean(KEY_LEARNING_ENABLED, true))
    /** 自学习开关：关闭后只使用已有记忆（以及用户明确说"记住……"），不再后台反思。 */
    val learningEnabled: StateFlow<Boolean> = _learningEnabled.asStateFlow()

    /** 记忆库内容，仅在界面可见期间订阅数据库。 */
    val memories: StateFlow<List<MemoryRecord>> by lazy {
        memory.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /** 记忆库不可用的原因（数据库损坏 / 缺少迁移等）；正常为 null。界面据此显示提示而不是误导性的"0 条记忆"。 */
    val memoryStoreError: StateFlow<String?> get() = memory.storeError

    private var reflectionJob: Job? = null
    /** 后台反思正在占用模型：此时引擎内部状态是 GENERATING，但对界面应当保持 READY（"静默"）。 */
    private var reflecting = false

    val modelDir: File
        get() = File(app.getExternalFilesDir(null) ?: app.filesDir, MODEL_DIR_NAME)

    // ====================================================================
    //  模型文件导入
    // ====================================================================

    /** 把用户选中的文件夹（SAF tree uri）里的模型文件复制到应用私有目录。 */
    fun importModel(treeUri: Uri) {
        if (importing) return
        if (llm != null) {
            warn("请先释放模型，再导入模型文件。")
            return
        }
        importing = true
        syncPhase()
        logEvent("正在导入模型文件。llm.mnn.weight 较大，请保持 App 在前台并等待复制完成……")

        viewModelScope.launch {
            try {
                val copied = withContext(Dispatchers.IO) { copyModelFiles(treeUri) }
                refreshModelFiles()
                logEvent("导入完成：${copied.joinToString(", ").ifEmpty { "没有找到可导入的模型文件" }}")
                val missing = _modelFiles.value.missing
                if (missing.isEmpty()) {
                    logEvent("必需文件已齐全。现在可以点击“初始化”。")
                } else {
                    logEvent("仍缺少：${missing.joinToString(", ")}。请确认选择的是直接包含 config.json 的文件夹。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                refreshModelFiles()
                logEvent("导入失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                importing = false
                syncPhase()
            }
        }
    }

    private suspend fun copyModelFiles(treeUri: Uri): List<String> {
        val sourceFolder = DocumentFile.fromTreeUri(app, treeUri)
            ?: throw IllegalStateException("无法读取所选文件夹")
        if (!sourceFolder.isDirectory) throw IllegalArgumentException("请选择解压后的模型文件夹，而不是单个文件")

        val dir = modelDir
        dir.mkdirs()
        val copied = mutableListOf<String>()
        for (name in REQUIRED_MODEL_FILES + OPTIONAL_MODEL_FILES) {
            coroutineContext.ensureActive()          // 页面彻底退出时尽快停止复制
            val source = sourceFolder.findFile(name) ?: continue
            if (!source.isFile) continue
            val destination = File(dir, name)
            val temporary = File(dir, "$name.importing")
            app.contentResolver.openInputStream(source.uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            } ?: throw IllegalStateException("无法读取文件：$name")

            if (destination.exists() && !destination.delete()) {
                temporary.delete()
                throw IllegalStateException("无法替换旧文件：$name")
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                throw IllegalStateException("无法保存文件：$name")
            }
            copied += name
        }
        return copied
    }

    // ====================================================================
    //  模型初始化 / 释放
    // ====================================================================

    fun initModel(
        threads: Int = 4,
        backend: String = "cpu",
        precision: String = "low",
        maxNewTokens: Int = LlmDefaults.MAX_NEW_TOKENS,
        maxAllTokens: Int = LlmDefaults.MAX_ALL_TOKENS,
        systemPrompt: String? = AdvisorPrompt.DEFAULT,
    ) {
        if (importing) {
            logEvent("模型文件正在导入，请稍候。")
            return
        }
        if (llm != null) {
            logEvent("模型对象已存在；如需重新初始化，请先释放模型。")
            return
        }
        refreshModelFiles()
        val missing = _modelFiles.value.missing
        if (missing.isNotEmpty()) {
            logEvent("无法初始化，缺少文件：${missing.joinToString(", ")}")
            return
        }

        val engine = MnnLlm(app)
        llm = engine
        _phase.value = ModelPhase.INITIALIZING
        logEvent("正在加载 MNN 原生库并初始化模型……")

        viewModelScope.launch {
            val result = engine.init(
                modelDir = modelDir,
                threads = threads,
                backend = backend,
                precision = precision,
                maxNewTokens = maxNewTokens,
                maxAllTokens = maxAllTokens,
                systemPrompt = systemPrompt,
            )
            result.fold(
                onSuccess = {
                    logEvent("✓ 模型初始化成功。现在可以输入问题并点击发送。")
                },
                onFailure = { error ->
                    logEvent("初始化失败：${error.message ?: error.javaClass.simpleName}")
                    if (llm === engine) llm = null
                    engine.release()
                    refreshModelFiles()
                },
            )
            syncPhase()
        }
    }

    /** 主动释放模型（对应界面上的"释放"按钮）。释放后聊天记录一并清空，因为模型端上下文已销毁。 */
    fun releaseModel() {
        val engine = llm ?: return
        if (_phase.value == ModelPhase.RELEASING) return
        _phase.value = ModelPhase.RELEASING
        logEvent("正在释放模型资源……")
        reflectionJob?.cancel()
        engine.release()                              // 非阻塞；会请求停止在途推理
        viewModelScope.launch {
            runCatching { engine.awaitReleased() }
            if (llm === engine) llm = null
            _messages.value = emptyList()
            logEvent("模型资源已释放。")
            refreshModelFiles()
            syncPhase()
        }
    }

    // ====================================================================
    //  对话
    // ====================================================================

    /**
     * 发送一条消息并开始流式生成。
     * @return true 表示已受理（UI 可以清空输入框）；false 表示被拒绝（模型未就绪 / 输入为空），原因已通过 toast 提示。
     */
    fun sendMessage(text: String): Boolean {
        val engine = llm
        // 注意：后台反思期间引擎内部是 GENERATING，但 phase 仍是 READY；这里以 phase 为准，发送时会先打断反思
        if (engine == null || _phase.value != ModelPhase.READY) {
            warn("模型尚未成功初始化，因此发送暂不可用。请先初始化模型。")
            return false
        }
        val prompt = text.trim()
        if (prompt.isEmpty()) {
            warn("请先输入问题。")
            return false
        }

        val assistantId = newMessageId()
        _messages.update {
            it + ChatMessage(newMessageId(), ChatRole.USER, prompt) +
                ChatMessage(assistantId, ChatRole.ASSISTANT, isStreaming = true)
        }
        _phase.value = ModelPhase.GENERATING
        logEvent("开始生成……")

        generationJob = viewModelScope.launch {
            val answer = StringBuilder()
            try {
                // 1) 用户优先：打断后台学习，并等 native 真正退出后再开始聊天
                reflectionJob?.cancelAndJoin()

                // 2) 记忆：处理"记住……"指令 + 检索相关记忆拼进系统提示词。
                //    记忆层任何失败都只会退回默认提示词，不会影响聊天。
                var systemOverride: String? = null
                if (_memoryEnabled.value) {
                    handleExplicitMemory(prompt)
                    val composed = memory.composeSystemPrompt(AdvisorPrompt.DEFAULT, prompt)
                    systemOverride = composed.systemPrompt
                    if (composed.used.isNotEmpty()) logEvent("🧠 本轮参考了 ${composed.used.size} 条记忆")
                }

                // chat() 的 callbackFlow 逻辑保持不变：本协程被取消 = 中断 native 推理
                engine.chat(prompt, systemPromptOverride = systemOverride).collect { piece ->
                    answer.append(piece)
                    updateMessage(assistantId) { it.copy(text = it.text + piece) }
                }
                val reason = engine.lastFinishReason
                logEvent("生成结束：${reason ?: "未知原因"}")
                if (reason == FinishReason.MAX_TOKENS) {
                    updateMessage(assistantId) { it.copy(text = it.text + "\n\n…（已达到长度上限）") }
                }

                // 3) 学习：只对"顺利结束"的回答做静默反思（用户中途点了停止则跳过）
                if (reason != FinishReason.STOPPED) scheduleReflection(engine, prompt, answer.toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                warn("生成失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                updateMessage(assistantId) { it.copy(isStreaming = false) }
                generationJob = null
                syncPhase()
            }
        }
        return true
    }

    /** 请求停止当前生成（非阻塞）。流会以 STOPPED 正常收尾，已生成的文本保留。 */
    fun stopGeneration() {
        val engine = llm ?: return
        if (_phase.value != ModelPhase.GENERATING) return
        engine.stop()
        logEvent("已请求停止生成。")
    }

    /** 清空界面对话与模型端对话历史；若静默反思正在运行，先取消并等待 native 推理退出。 */
    fun newChat() {
        if (_phase.value == ModelPhase.GENERATING) {
            toastChannel.trySend("请先停止当前生成")
            return
        }
        _messages.value = emptyList()
        val engine = llm ?: return
        val pendingReflection = reflectionJob
        if (pendingReflection != null) {
            viewModelScope.launch {
                try {
                    pendingReflection.cancelAndJoin()
                    if (reflectionJob === pendingReflection) reflectionJob = null
                    if (llm !== engine || !engine.isReady) return@launch
                    engine.reset()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    logEvent("清空上下文失败：${error.message ?: error.javaClass.simpleName}")
                }
            }
            return
        }
        if (!engine.isReady) return
        viewModelScope.launch {
            try {
                engine.reset()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logEvent("清空上下文失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    // ====================================================================
    //  认知记忆
    // ====================================================================

    private suspend fun handleExplicitMemory(userText: String) {
        when (val result = memory.rememberExplicit(userText)) {
            null -> Unit
            is ExplicitResult.Saved -> logEvent("🧠 已记住：${result.result.record.content}")
            is ExplicitResult.Rejected -> warn("这条内容没有写入长期记忆：${result.reason}")
        }
    }

    /**
     * 回答结束后的静默反思。只在"像是包含值得记住的信息"时才触发（省电），
     * 并且用户一发新消息就会被 [sendMessage] 取消 —— 聊天永远优先于学习。
     */
    private fun scheduleReflection(engine: MnnLlm, userText: String, answer: String) {
        if (!_memoryEnabled.value || !_learningEnabled.value) return
        if (!ReflectionGate.shouldReflect(userText, answer)) return

        reflectionJob?.cancel()
        reflectionJob = viewModelScope.launch {
            try {
                delay(REFLECTION_DELAY_MS)
                if (_phase.value != ModelPhase.READY || llm !== engine) return@launch
                reflecting = true
                val reflector = ReflectionLlm { system, user, maxTokens ->
                    engine.complete(system, user, maxTokens).getOrNull()
                }
                val outcome = memory.reflect(reflector, userText, answer)
                for (item in outcome.results) {
                    when (item.kind) {
                        UpsertKind.ADDED -> logEvent("🧠 已记住：${item.record.content}")
                        UpsertKind.MERGED -> logEvent("🧠 已更新记忆：${item.record.content}")
                        UpsertKind.REINFORCED -> Unit   // 重复出现的旧记忆：静默强化，不打扰
                    }
                }
            } finally {
                reflecting = false
            }
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        _memoryEnabled.value = enabled
        prefs.edit().putBoolean(KEY_MEMORY_ENABLED, enabled).apply()
        if (!enabled) reflectionJob?.cancel()
    }

    fun setLearningEnabled(enabled: Boolean) {
        _learningEnabled.value = enabled
        prefs.edit().putBoolean(KEY_LEARNING_ENABLED, enabled).apply()
        if (!enabled) reflectionJob?.cancel()
    }

    fun deleteMemory(id: Long) = launchMemoryOp("删除记忆") { memory.delete(id) }

    fun clearMemories() = launchMemoryOp("清空记忆") {
        memory.clear()
        logEvent("🧠 已清空全部长期记忆。")
    }

    fun setMemoryPinned(id: Long, pinned: Boolean) = launchMemoryOp("更新置顶") { memory.setPinned(id, pinned) }

    private fun launchMemoryOp(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                warn("$what 失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    // ====================================================================
    //  日志 / 提示
    // ====================================================================

    fun logEvent(message: String) {
        _events.update { (it + message).takeLast(MAX_EVENT_LINES) }
    }

    private fun warn(message: String) {
        logEvent(message)
        toastChannel.trySend(message)
    }

    // ====================================================================
    //  内部工具
    // ====================================================================

    private fun newMessageId(): Long = nextMessageId++

    private inline fun updateMessage(id: Long, crossinline transform: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** 根据 importing 标志与 MnnLlm 的实际状态重新计算 [phase]。 */
    private fun syncPhase() {
        _phase.value = when {
            importing -> ModelPhase.IMPORTING
            else -> when (llm?.currentState) {
                null, MnnLlm.State.NEW, MnnLlm.State.FAILED, MnnLlm.State.RELEASED -> ModelPhase.UNLOADED
                MnnLlm.State.INITIALIZING -> ModelPhase.INITIALIZING
                MnnLlm.State.READY -> ModelPhase.READY
                MnnLlm.State.GENERATING -> if (reflecting) ModelPhase.READY else ModelPhase.GENERATING
                MnnLlm.State.RELEASING -> ModelPhase.RELEASING
            }
        }
    }

    private fun scanModelFiles(): ModelFilesState {
        val dir = modelDir
        if (!dir.exists()) dir.mkdirs()
        val entries = REQUIRED_MODEL_FILES.map { name ->
            val file = File(dir, name)
            ModelFileEntry(name, present = file.isFile && file.length() > 0L, sizeBytes = file.length())
        }
        return ModelFilesState(dir.absolutePath, entries)
    }

    private fun refreshModelFiles() {
        _modelFiles.value = scanModelFiles()
    }

    // ====================================================================
    //  生命周期：只有 Activity 真正结束时才会走到这里（旋转 / 深色模式切换不会）
    // ====================================================================

    override fun onCleared() {
        // viewModelScope 此时已被取消 → 在途 chat() 的 awaitClose 会请求 native 停止；
        // release() 非阻塞且幂等，销毁动作排在单线程调度器队列末尾，一定晚于在途推理退出。
        llm?.release()
        llm = null
        toastChannel.close()
        super.onCleared()
    }
}

