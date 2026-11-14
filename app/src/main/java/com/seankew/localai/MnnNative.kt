package com.seankew.localai

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import java.io.File

/** 与 llm_jni.cpp 的 ErrCode 一一对应 */
object ErrorCode {
    const val OK = 0
    const val INVALID_HANDLE = 1
    const val BAD_STATE = 2
    const val MODEL_FILES_MISSING = 3
    const val CREATE_FAILED = 4
    const val LOAD_FAILED = 5
    const val GENERATE_FAILED = 6
    const val CALLBACK_FAILED = 7
    const val NATIVE_EXCEPTION = 8
    const val OUT_OF_MEMORY = 9
    const val BAD_ARGUMENT = 10
    const val LIB_LOAD_FAILED = 11   // 仅 Kotlin 侧使用
    const val UNKNOWN = -1

    fun name(code: Int) = when (code) {
        OK -> "OK"; INVALID_HANDLE -> "INVALID_HANDLE"; BAD_STATE -> "BAD_STATE"
        MODEL_FILES_MISSING -> "MODEL_FILES_MISSING"; CREATE_FAILED -> "CREATE_FAILED"
        LOAD_FAILED -> "LOAD_FAILED"; GENERATE_FAILED -> "GENERATE_FAILED"
        CALLBACK_FAILED -> "CALLBACK_FAILED"; NATIVE_EXCEPTION -> "NATIVE_EXCEPTION"
        OUT_OF_MEMORY -> "OUT_OF_MEMORY"; BAD_ARGUMENT -> "BAD_ARGUMENT"
        LIB_LOAD_FAILED -> "LIB_LOAD_FAILED"; else -> "UNKNOWN($code)"
    }
}

/** JNI 侧通过 (String, int) 构造器抛出，所以必须 @Keep + @JvmOverloads */
class MnnException @JvmOverloads constructor(
    message: String,
    val code: Int = ErrorCode.UNKNOWN,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    override val message: String get() = "[${ErrorCode.name(code)}] ${super.message}"
}

/** native 通过 onToken([B)Z 回调；返回 false 表示请求停止。字节流保证是完整的 UTF-8 片段。 */
fun interface TokenCallback {
    fun onToken(utf8: ByteArray): Boolean
}
object MnnNative {
    private const val TAG = "MnnNative"
    private val lock = Any()
    @Volatile private var loaded = false

    // (库名, 是否必需)。可选库缺失只打 warn，必需库缺失会抛 MnnException 并输出诊断
    private val LIB_CHAIN = listOf(
        // This workflow builds the LLM runtime into the monolithic libMNN.so.
        "MNN" to true,
        "mnnllm_jni" to true,
    )

    // ---- JNI（由 JNI_OnLoad 里的 RegisterNatives 绑定）----
    @JvmStatic external fun nativeCreate(configPath: String): Long
    @JvmStatic external fun nativeLoad(handle: Long, extraConfigJson: String)
    @JvmStatic external fun nativeGenerate(handle: Long, gen: Long, promptUtf8: ByteArray,
                                           maxNewTokens: Int, cb: TokenCallback): Int
    @JvmStatic external fun nativeStop(handle: Long, gen: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeRelease(handle: Long)
    @JvmStatic external fun nativeSetVerbose(enabled: Boolean)
    @JvmStatic external fun nativeBuildInfo(): String

    fun ensureLoaded(context: Context? = null) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            logEnvironment(context)
            for ((name, required) in LIB_CHAIN) {
                try {
                    System.loadLibrary(name)
                    Log.i(TAG, "loaded lib$name.so")
                } catch (e: LinkageError) {
                    if (required) {
                        Log.e(TAG, "FAILED to load lib$name.so: ${e.message}\n${explain(e)}", e)
                        dumpNativeLibDir(context)
                        throw MnnException("cannot load lib$name.so: ${e.message}", ErrorCode.LIB_LOAD_FAILED, e)
                    } else {
                        Log.w(TAG, "optional lib$name.so not loaded: ${e.message}")
                    }
                }
            }
            loaded = true
            val debuggable = context?.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) != 0
            nativeSetVerbose(debuggable)
            Log.i(TAG, "native ready: ${nativeBuildInfo()}")
        }
    }

    private fun logEnvironment(context: Context?) {
        Log.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL}, sdk=${Build.VERSION.SDK_INT}, " +
                "abis=${Build.SUPPORTED_ABIS.joinToString()}, process64=${android.os.Process.is64Bit()}")
        context?.applicationInfo?.let { Log.i(TAG, "nativeLibraryDir=${it.nativeLibraryDir}") }
    }

    private fun dumpNativeLibDir(context: Context?) {
        val dir = context?.applicationInfo?.nativeLibraryDir?.let(::File) ?: return
        val files = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            Log.e(TAG, "nativeLibraryDir is EMPTY. Check: jniLibs/<abi>/ path, ndk.abiFilters, " +
                    "packaging.jniLibs.useLegacyPackaging=true (or android:extractNativeLibs=\"true\")")
        } else {
            files.forEach { Log.e(TAG, "  ${it.name}  ${it.length()} bytes") }
        }
    }

    private val SYMBOL_RE = Regex("""cannot locate symbol "([^"]+)"(?: referenced by "([^"]+)")?""")
    private val MISSING_RE = Regex("""(?:library "([^"]+)" not found|couldn't find "([^"]+)")""")

    /** 把常见的 dlopen / linker 报错翻译成可操作的排查建议 */
    fun explain(e: Throwable): String {
        val m = e.message.orEmpty()
        SYMBOL_RE.find(m)?.let {
            val sym = it.groupValues[1]; val by = it.groupValues[2].ifEmpty { "?" }
            return "符号未找到: $sym\n  被引用方: $by\n" +
                    "  常见原因: libMNN.so / libllm.so / libMNN_Express.so 不是同一次编译产物（MNN 版本或编译选项不一致），" +
                    "或 STL（c++_shared / c++_static）、NDK 版本不一致。\n" +
                    "  排查: `c++filt $sym` 看是哪个 MNN 符号；`llvm-nm -D --defined-only libMNN.so | grep <关键字>` " +
                    "确认 libMNN.so 是否导出它；确保三个库用同一份源码、同一 NDK 重新编译。"
        }
        MISSING_RE.find(m)?.let {
            val lib = it.groupValues[1].ifEmpty { it.groupValues[2] }
            return "依赖库未找到: $lib\n  检查它是否放进了 jniLibs/<abi>/ 并被打进 APK；" +
                    "若是 libc++_shared.so，需要在 gradle 里设置 -DANDROID_STL=c++_shared 或手动打包。"
        }
        return when {
            "32-bit instead of 64-bit" in m || "wrong ELF class" in m ->
                "ABI 不匹配: 进程是 64 位但库是 32 位（或反之）。检查 abiFilters 与 jniLibs 目录。"
            "bad ELF magic" in m || "file too short" in m ->
                "so 文件损坏/不完整（0 字节或被当文本提交）。检查 git LFS、构建产物与拷贝过程。"
            "alignment" in m || "page size" in m ->
                "ELF 对齐不满足设备页大小（Android 15+ 的 16KB 页设备）。用 NDK r28+ 重新编译，" +
                        "或给链接器加 -Wl,-z,max-page-size=16384。"
            "JNI_ERR" in m || "JNI_OnLoad" in m ->
                "JNI_OnLoad 失败：过滤 logcat 标签 MnnLlmJni 看具体原因（常见：R8 改了类名 / 包名与 C++ 常量不一致）。"
            else -> "未识别的加载错误，请把上面的完整 message 与 MnnLlmJni 标签日志一起排查。"
        }
    }
}
