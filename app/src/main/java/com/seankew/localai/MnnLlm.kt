package com.seankew.localai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class FinishReason { EOS, STOPPED, MAX_TOKENS }

/**
 * 线程模型：所有会触碰 native 句柄的 init/generate/reset/release 都在同一个单线程调度器上串行执行。
 * stop() 与 release() 的"请求停止"部分是线程安全且非阻塞的，可以从任意线程调用。
 */
class MnnLlm(private val context: Context) : AutoCloseable {

    enum class State { NEW, INITIALIZING, READY, GENERATING, RELEASING, RELEASED, FAILED }

    private companion object { const val TAG = "MnnLlm" }

    private val state = AtomicReference(State.NEW)
    val currentState: State get() = state.get()
    val isReady: Boolean get() = state.get() == State.READY
    val isLoaded: Boolean get() = isReady

    @Volatile private var handle = 0L          // native 注册表 id，不是指针
    @Volatile private var defaultMaxNewTokens = LlmDefaults.MAX_NEW_TOKENS
    @Volatile private var activeGen = 0L
    @Volatile private var activeCancel: AtomicBoolean? = null
    private val genCounter = AtomicLong(0)

    // ---- 对话历史仅在 llmScope 的单线程 dispatcher 上读写 ----
    private val history = ArrayList<ChatTurn>()
    @Volatile private var systemPrompt: String? = AdvisorPrompt.DEFAULT
    @Volatile var maxHistoryChars: Int = 3000

    /** 运行时切换系统提示词，下一轮对话开始时生效；传 null 可关闭。 */
    fun setSystemPrompt(text: String?) {
        systemPrompt = text
    }

    @Volatile var lastFinishReason: FinishReason? = null
        private set

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "mnn-llm", 8L * 1024 * 1024).apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val llmScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val releasedSignal = CompletableDeferred<Unit>()

    // ------------------------------------------------------------------ init
    suspend fun init(
        modelDir: File,
        threads: Int = 4,
        backend: String = "cpu",
        precision: String = "low",
        maxNewTokens: Int = LlmDefaults.MAX_NEW_TOKENS,
        maxAllTokens: Int = LlmDefaults.MAX_ALL_TOKENS,
        systemPrompt: String? = AdvisorPrompt.DEFAULT,
    ): Result<Unit> {
        require(maxNewTokens > 0) { "maxNewTokens must be > 0" }
        require(maxAllTokens >= maxNewTokens) { "maxAllTokens must be >= maxNewTokens" }
        while (true) {
            val cur = state.get()
            if (cur != State.NEW && cur != State.FAILED)
                return Result.failure(IllegalStateException("init() not allowed in state $cur"))
            if (state.compareAndSet(cur, State.INITIALIZING)) break
        }
        val appContext = context.applicationContext
        return withContext(dispatcher) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                MnnNative.ensureLoaded(appContext)
                val config = File(modelDir, "config.json")
                Log.i(TAG, "init: dir=${modelDir.absolutePath} config.exists=${config.exists()} " +
                        "threads=$threads backend=$backend precision=$precision")

                val h = MnnNative.nativeCreate(config.absolutePath)
                handle = h
                if (state.get() != State.INITIALIZING)
                    throw MnnException("released during init", ErrorCode.BAD_STATE)

                val extra = JSONObject()
                    .put("tmp_path", appContext.cacheDir.absolutePath)
                    .put("thread_num", threads)
                    .put("backend_type", backend)
                    .put("precision", precision)
                    .put("max_new_tokens", maxNewTokens)
                    .put("max_all_tokens", maxAllTokens)
                    .toString()
                MnnNative.nativeLoad(h, extra)
                defaultMaxNewTokens = maxNewTokens
                this@MnnLlm.systemPrompt = systemPrompt

                if (!state.compareAndSet(State.INITIALIZING, State.READY))
                    throw MnnException("released during init", ErrorCode.BAD_STATE)
                Log.i(TAG, "init ok in ${SystemClock.elapsedRealtime() - t0} ms")
                Result.success(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "init failed after ${SystemClock.elapsedRealtime() - t0} ms: ${t.message}", t)
                // 就在串行线程上，回收已创建的 native 句柄不会与推理竞争
                val h = handle
                if (h != 0L) {
                    runCatching { MnnNative.nativeRelease(h) }
                        .onFailure { Log.w(TAG, "cleanup nativeRelease failed", it) }
                    handle = 0L
                }
                state.compareAndSet(State.INITIALIZING, State.FAILED)
                Result.failure(t)
            }
        }
    }

    // ------------------------------------------------------------------ chat
    /**
     * 同一时刻只允许一次生成；状态不是 READY 时 Flow 以 IllegalStateException 结束。
     * 取消收集（或调用 stop()）会真正中断 native 推理，通常在下一个 token 内生效。
     */
    fun chat(userText: String, maxNewTokens: Int = defaultMaxNewTokens): Flow<String> = callbackFlow {
        require(maxNewTokens > 0) { "maxNewTokens must be > 0" }
        if (!state.compareAndSet(State.READY, State.GENERATING)) {
            close(IllegalStateException("MnnLlm is ${state.get()}; chat() requires READY"))
            return@callbackFlow
        }

        val h = handle
        val gen = genCounter.incrementAndGet()
        val cancelled = AtomicBoolean(false)
        activeGen = gen
        activeCancel = cancelled
        val assistantText = StringBuilder()

        val cb = TokenCallback { bytes ->
            try {
                if (cancelled.get() || state.get() != State.GENERATING) {
                    false
                } else {
                    val piece = String(bytes, Charsets.UTF_8)
                    assistantText.append(piece)
                    trySend(piece).isSuccess
                }
            } catch (t: Throwable) {
                Log.e(TAG, "token callback failed", t)
                false
            }
        }

        llmScope.launch {
            var failure: Throwable? = null
            try {
                if (cancelled.get() || state.get() != State.GENERATING) {
                    lastFinishReason = FinishReason.STOPPED
                } else {
                    trimHistory()
                    val prompt = ChatMl.build(systemPrompt, history, userText)
                    Log.i(TAG, "chat: gen=$gen promptChars=${prompt.length} historyTurns=${history.size}")

                    val code = MnnNative.nativeGenerate(
                        h, gen, prompt.toByteArray(Charsets.UTF_8), maxNewTokens, cb
                    )
                    lastFinishReason = when (code) {
                        0 -> FinishReason.EOS
                        1 -> FinishReason.STOPPED
                        else -> FinishReason.MAX_TOKENS
                    }
                    commitTurn(userText, assistantText.toString())
                    Log.i(TAG, "generate finished: gen=$gen reason=$lastFinishReason answerChars=${assistantText.length}")
                }
            } catch (e: MnnException) {
                Log.e(TAG, "generate failed: ${e.message}", e)
                failure = e
            } catch (t: Throwable) {
                Log.e(TAG, "generate crashed: ${t.message}", t)
                failure = MnnException("generate failed: ${t.message}", ErrorCode.NATIVE_EXCEPTION, t)
            } finally {
                if (activeGen == gen) { activeGen = 0L; activeCancel = null }
                state.compareAndSet(State.GENERATING, State.READY)
            }
            close(failure)
        }

        awaitClose {
            cancelled.set(true)
            runCatching { MnnNative.nativeStop(h, gen) }
        }
    }.buffer(Channel.UNLIMITED)
    /** 请求停止当前生成（非阻塞，线程安全）。 */
    fun stop() {
        val h = handle
        val g = activeGen
        if (h == 0L || g == 0L || state.get() != State.GENERATING) return
        activeCancel?.set(true)
        runCatching { MnnNative.nativeStop(h, g) }.onFailure { Log.w(TAG, "stop failed", it) }
    }

    /** 停止并等待当前生成真正退出（用于"打断后立刻重新提问"）。 */
    suspend fun stopAndAwait() {
        if (isTerminal()) return
        stop()
        withContext(dispatcher) { /* 排在在途生成之后，进入这里即代表 native 已退出 */ }
    }

    suspend fun reset() {
        withContext(dispatcher) {
            val s = state.get()
            check(s == State.READY || s == State.GENERATING) { "reset() not allowed in state $s" }
            history.clear()
            MnnNative.nativeReset(handle)
        }
    }

    // 只在 llm 线程调用。
    private fun commitTurn(user: String, assistant: String) {
        val answer = assistant.trim()
        if (answer.isEmpty()) return
        history += ChatTurn(ChatTurn.Role.USER, user)
        history += ChatTurn(ChatTurn.Role.ASSISTANT, answer)
    }

    // 按完整 user/assistant 回合丢弃最旧历史，避免孤立角色消息。
    private fun trimHistory() {
        var totalChars = history.sumOf { it.content.length }
        val budget = maxHistoryChars.coerceAtLeast(0)
        while (history.size >= 2 && totalChars > budget) {
            totalChars -= history.removeAt(0).content.length
            totalChars -= history.removeAt(0).content.length
        }
    }

    // --------------------------------------------------------------- release
    /**
     * 非阻塞、幂等、线程安全，可直接在 onCleared()/onDestroy() 里调用：
     * 1) 立刻标记 RELEASING，拒绝新请求；
     * 2) 立刻请求停止在途推理（原子标志，不等锁）；
     * 3) 销毁动作排在单线程调度器队列末尾 —— 一定在在途推理退出之后执行；
     * native 层 release 还有 mutex + released 标志再保护一层。
     */
    fun release() {
        while (true) {
            val cur = state.get()
            if (cur == State.RELEASING || cur == State.RELEASED) return
            if (state.compareAndSet(cur, State.RELEASING)) break
        }
        activeCancel?.set(true)
        val h = handle
        if (h != 0L) runCatching { MnnNative.nativeStop(h, 0L) }
            .onFailure { Log.w(TAG, "nativeStop during release failed", it) }

        llmScope.launch {
            try {
                val hh = handle
                if (hh != 0L) {
                    handle = 0L
                    MnnNative.nativeRelease(hh)
                    Log.i(TAG, "released native handle $hh")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "nativeRelease failed", t)
            } finally {
                state.set(State.RELEASED)
                releasedSignal.complete(Unit)
                executor.shutdown()
            }
        }
    }

    suspend fun awaitReleased() = releasedSignal.await()
    override fun close() = release()

    private fun isTerminal(): Boolean =
        state.get().let { it == State.RELEASING || it == State.RELEASED }
}
