package com.seankew.localai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

fun interface TokenCallback {
    fun onToken(text: String): Boolean
}

class MnnLlm(private val context: Context) : AutoCloseable {
    companion object {
        init {
            // MNNChat's Android prebuilt contains the LLM runtime in libMNN.so.
            System.loadLibrary("MNN")
            System.loadLibrary("mnnllm_jni")
        }
    }

    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val closed = AtomicBoolean(false)
    @Volatile private var handle = 0L

    val isLoaded: Boolean get() = handle != 0L && !closed.get()

    suspend fun init(modelDir: File, threads: Int = 4): Boolean = withContext(dispatcher) {
        check(!closed.get()) { "MnnLlm has been closed" }
        if (handle != 0L) return@withContext true
        val config = File(modelDir, "config.json")
        require(config.isFile) { "Missing model config: ${config.absolutePath}" }
        val created = nativeCreate(config.absolutePath)
        if (created == 0L) return@withContext false
        handle = created
        val cache = context.cacheDir.absolutePath
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val extra = """{"tmp_path":"$cache","thread_num":$threads,"backend_type":"cpu","precision":"low"}"""
        val loaded = nativeLoad(created, extra)
        if (!loaded) {
            nativeRelease(created)
            handle = 0L
        }
        loaded
    }

    fun chat(prompt: String, maxNewTokens: Int = 256): Flow<String> = callbackFlow {
        check(isLoaded) { "Model is not loaded" }
        require(maxNewTokens > 0)
        val ptr = handle
        val completed = AtomicBoolean(false)
        val scope = CoroutineScope(dispatcher)
        val worker: Job = scope.launch {
            try {
                nativeGenerate(ptr, prompt, maxNewTokens, TokenCallback { token ->
                    trySend(token).isSuccess && !closed.get()
                })
            } finally {
                completed.set(true)
                close()
            }
        }
        awaitClose {
            if (!completed.get()) nativeStop(ptr)
            worker.cancel()
            scope.cancel()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun reset() = withContext(dispatcher) {
        if (handle != 0L && !closed.get()) nativeReset(handle)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val ptr = handle
        handle = 0L
        if (ptr != 0L) {
            nativeStop(ptr)
            nativeRelease(ptr)
        }
    }

    private external fun nativeCreate(configPath: String): Long
    private external fun nativeLoad(ptr: Long, extraConfig: String): Boolean
    private external fun nativeGenerate(ptr: Long, prompt: String, maxNewTokens: Int, callback: TokenCallback)
    private external fun nativeStop(ptr: Long)
    private external fun nativeReset(ptr: Long)
    private external fun nativeRelease(ptr: Long)
}
