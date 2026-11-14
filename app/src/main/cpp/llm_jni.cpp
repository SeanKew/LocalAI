// llm_jni.cpp — MNN LLM JNI 桥接
// 如果改包名，只需同步修改下面三个常量 + Kotlin 侧 package。
#include <jni.h>
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cxxabi.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <new>
#include <ostream>
#include <stdexcept>
#include <streambuf>
#include <string>
#include <thread>
#include <type_traits>
#include <typeinfo>
#include <unordered_map>
#include <vector>

#include <MNN/MNNDefine.h>
#include "llm/llm.hpp"

using MNN::Transformer::Llm;

static constexpr const char* kNativeClass    = "com/seankew/localai/MnnNative";
static constexpr const char* kExceptionClass = "com/seankew/localai/MnnException";
static constexpr const char* kCallbackClass  = "com/seankew/localai/TokenCallback";

// 生成结束标记：由 MNN 在正常结束时写入输出流，我们在流里识别并剥掉
static constexpr const char* kEndMarker = "<eop>";
// 除 MNN 的 end marker 外，也识别 Qwen ChatML / EOS 的可见形式。
static constexpr const char* kStopMarkers[] = {
    kEndMarker, "<|im_end|>", "<|endoftext|>", "<|im_start|>"
};
// Kotlin 传入完整 ChatML；C++ 只编码 token，不再让 MNN 二次套用 prompt template。
static constexpr bool kPromptIsFinal = true;
// 生成被中断/达到上限后，KV cache 里的历史不完整，默认 reset 以免污染下一轮。
static constexpr bool kResetOnAbnormalEnd = true;

// =============================== 日志 ===============================
#define LOG_TAG "MnnLlmJni"
static std::atomic<bool> g_verbose{true};
#define LOGD(...) do { if (g_verbose.load(std::memory_order_relaxed)) \
                       __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using Clock = std::chrono::steady_clock;
static inline long long msSince(Clock::time_point t) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t).count();
}

// =============================== 错误码（与 Kotlin ErrorCode 对应） ===============================
enum ErrCode : jint {
    ERR_OK = 0, ERR_INVALID_HANDLE = 1, ERR_BAD_STATE = 2, ERR_MODEL_FILES = 3, ERR_CREATE = 4,
    ERR_LOAD = 5, ERR_GENERATE = 6, ERR_CALLBACK = 7, ERR_NATIVE_EXCEPTION = 8, ERR_OOM = 9,
    ERR_BAD_ARGUMENT = 10
};
enum FinishReason : jint { REASON_EOS = 0, REASON_STOPPED = 1, REASON_MAX_TOKENS = 2 };

class MnnError : public std::runtime_error {
public:
    MnnError(jint c, const std::string& m) : std::runtime_error(m), code(c) {}
    jint code;
};

static std::string typeName(const std::type_info& ti) {
    int status = 0;
    char* p = abi::__cxa_demangle(ti.name(), nullptr, nullptr, &status);
    std::string r = (status == 0 && p) ? p : ti.name();
    free(p);
    return r;
}

// =============================== JNI 全局缓存 ===============================
static JavaVM*   g_vm = nullptr;
static jclass    g_exceptionClass = nullptr;   // GlobalRef
static jmethodID g_exceptionCtor  = nullptr;   // (Ljava/lang/String;I)V
static jmethodID g_onToken        = nullptr;   // TokenCallback.onToken([B)Z

static void throwJava(JNIEnv* env, jint code, const std::string& msg) {
    if (env->ExceptionCheck()) {
        LOGW("a Java exception is already pending, skip throwing: %s", msg.c_str());
        return;
    }
    // NewStringUTF 只能吃合法 (M)UTF-8，这里把异常消息净化成可见 ASCII
    std::string safe;
    safe.reserve(msg.size());
    for (unsigned char c : msg) safe.push_back((c >= 0x20 && c < 0x7F) || c == '\n' ? (char)c : '?');
    jstring jmsg = env->NewStringUTF(safe.c_str());
    jobject ex = jmsg ? env->NewObject(g_exceptionClass, g_exceptionCtor, jmsg, code) : nullptr;
    if (ex) env->Throw(static_cast<jthrowable>(ex));
    // 若 NewStringUTF/NewObject 失败，JVM 已经挂起 OOM 等异常，直接返回即可
    if (jmsg) env->DeleteLocalRef(jmsg);
    if (ex) env->DeleteLocalRef(ex);
}

// 所有 JNI 入口都包一层：C++ 异常绝不越过 JNI 边界
template <class F>
static auto safeCall(JNIEnv* env, const char* fn, F&& f) -> decltype(f()) {
    using R = decltype(f());
    try {
        return f();
    } catch (const MnnError& e) {
        LOGE("%s failed: code=%d, %s", fn, (int)e.code, e.what());
        throwJava(env, e.code, std::string(fn) + ": " + e.what());
    } catch (const std::bad_alloc&) {
        LOGE("%s: std::bad_alloc (native out of memory?)", fn);
        throwJava(env, ERR_OOM, std::string(fn) + ": native out of memory");
    } catch (const std::exception& e) {
        const std::string tn = typeName(typeid(e));
        LOGE("%s: C++ exception %s: %s", fn, tn.c_str(), e.what());
        throwJava(env, ERR_NATIVE_EXCEPTION, std::string(fn) + ": " + tn + ": " + e.what());
    } catch (...) {
        LOGE("%s: unknown C++ exception", fn);
        throwJava(env, ERR_NATIVE_EXCEPTION, std::string(fn) + ": unknown C++ exception");
    }
    if constexpr (!std::is_void_v<R>) return R{};
}

static void terminateHandler() {
    LOGE("std::terminate called");
    if (auto ep = std::current_exception()) {
        try { std::rethrow_exception(ep); }
        catch (const std::exception& e) { LOGE("uncaught C++ exception: %s", e.what()); }
        catch (...) { LOGE("uncaught non-std exception"); }
    }
    std::abort();
}

// =============================== MNN API 适配层 ===============================
// llm.hpp 的接口随 MNN 版本变化。所有对 MNN 的调用都集中在这里，
// 编译报错时只需要对照你分支里的 llm.hpp 改这几个函数。
namespace llm_api {

// load()/set_config() 在不同版本里返回 void 或 bool，用 SFINAE 兼容两种
template <class T> auto callLoad(T& l, int) -> decltype(static_cast<bool>(l.load())) { return static_cast<bool>(l.load()); }
template <class T> bool callLoad(T& l, long) { l.load(); return true; }

template <class T> auto callSetConfig(T& l, const std::string& c, int) -> decltype(static_cast<bool>(l.set_config(c))) { return static_cast<bool>(l.set_config(c)); }
template <class T> bool callSetConfig(T& l, const std::string& c, long) { l.set_config(c); return true; }

// MNN 3.6.2 的 tokenizer_encode(string) 直接分词，不调用 apply_chat_template。
inline std::vector<int> encode(Llm& l, const std::string& prompt) {
    return l.tokenizer_encode(prompt);
}
// 将已经编码的完整 ChatML token 序列交给 MNN，不触发 string response 的二次模板处理。
inline void startFromIds(Llm& l, const std::vector<int>& ids, std::ostream* os, const char* endMark) {
    l.response(ids, os, endMark, 1);
}
// 旧行为保留为显式回退选项。
inline void startAndFirstToken(Llm& l, const std::string& prompt, std::ostream* os, const char* endMark) {
    l.response(prompt, os, endMark, 1);
}
// MNN 3.6.2 暴露 current_token；在每次增量生成后读取最新 token id。
inline int lastTokenId(Llm& l) {
    const auto* ctx = l.getContext();
    return ctx ? static_cast<int>(ctx->current_token) : -1;
}
// 再生成 1 个 token
inline void step(Llm& l) { l.generate(1); }
// 是否已遇到 EOS（部分版本叫 stoped()，拼写就是这样）
inline bool finished(Llm& l) { return l.stoped(); }
inline void reset(Llm& l) { l.reset(); }

} // namespace llm_api

// =============================== 句柄与注册表 ===============================
struct LlmHandle {
    std::unique_ptr<Llm> llm;
    std::mutex mu;                               // 串行化 load / generate / reset / release
    bool loaded = false;                         // 受 mu 保护
    std::atomic<bool> stop_requested{false};     // 请求中断当前推理
    std::atomic<bool> released{false};           // 已进入释放流程，任何新操作都应拒绝
    std::atomic<uint64_t> active_gen{0};         // 正在运行的那一代；0 = 空闲
    int imStartId = -1, imEndId = -1, eotId = -1; // load 时验证并缓存 Qwen2.5 ChatML token id

    bool isStopId(int token) const {
        return token >= 0 && (token == imEndId || token == eotId);
    }

    bool shouldStop() const {
        return stop_requested.load(std::memory_order_acquire) ||
               released.load(std::memory_order_acquire);
    }
};

// Kotlin 只拿到整数 id，不接触指针：过期 id 查不到 → 返回错误，而不是野指针
static std::mutex g_regMu;
static std::unordered_map<jlong, std::shared_ptr<LlmHandle>> g_registry;
static std::atomic<jlong> g_nextId{1};

static std::shared_ptr<LlmHandle> findHandle(jlong id) {
    std::lock_guard<std::mutex> lk(g_regMu);
    auto it = g_registry.find(id);
    return it == g_registry.end() ? nullptr : it->second;
}
static std::shared_ptr<LlmHandle> requireHandle(jlong id) {
    auto h = findHandle(id);
    if (!h) throw MnnError(ERR_INVALID_HANDLE, "invalid or already released handle " + std::to_string((long long)id));
    return h;
}

static void resetQuietly(LlmHandle& h) {
    try { if (h.llm) llm_api::reset(*h.llm); }
    catch (const std::exception& e) { LOGW("reset() threw: %s", e.what()); }
    catch (...) { LOGW("reset() threw unknown exception"); }
}

// =============================== 工具函数 ===============================
static std::string toStd(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (!c) throw MnnError(ERR_OOM, "GetStringUTFChars failed");
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}
static std::string bytesToString(JNIEnv* env, jbyteArray arr) {
    const jsize n = env->GetArrayLength(arr);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(&s[0]));
    return s;
}

// 返回 ≤limit 的最大长度，使 s[0..len) 不会在 UTF-8 多字节字符中间截断
static size_t utf8SafeLen(const std::string& s, size_t limit) {
    if (limit == 0) return 0;
    size_t i = limit;
    for (int back = 0; i > 0 && back < 4; ++back) {
        const unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) { --i; continue; }               // continuation byte
        const size_t need = (c >= 0xF0) ? 4 : (c >= 0xE0) ? 3 : (c >= 0xC0) ? 2 : 1;
        const size_t have = limit - (i - 1);
        return have >= need ? limit : (i - 1);
    }
    return limit;   // 连续 4 个 continuation：数据已损坏，原样放行
}
// 返回最早出现的停止标记位置；which 返回对应标记。
static size_t findStopMarker(const std::string& s, const char** which) {
    size_t best = std::string::npos;
    for (const char* marker : kStopMarkers) {
        const size_t pos = s.find(marker);
        if (pos < best) {
            best = pos;
            if (which) *which = marker;
        }
    }
    return best;
}

// 末尾若是任一停止标记的前缀（如 "<|im_e"），先扣住，等下一块数据再判断。
static size_t partialMarkerSuffix(const std::string& s) {
    size_t best = 0;
    for (const char* marker : kStopMarkers) {
        const size_t markerLen = std::strlen(marker);
        for (size_t k = std::min(markerLen - 1, s.size()); k > best; --k) {
            if (s.compare(s.size() - k, k, marker, k) == 0) {
                best = k;
                break;
            }
        }
    }
    return best;
}

// 启动自检：Qwen2.5 的三个核心 ChatML token 必须分别编码为预期的单个 token id。
static void probeChatMl(LlmHandle& h) {
    auto one = [&](const char* marker, int expectedId) -> int {
        const std::vector<int> ids = llm_api::encode(*h.llm, marker);
        const int actualId = ids.size() == 1 ? ids[0] : -1;
        LOGI("  probe encode(%s) -> %zu id(s), actual=%d expected=%d%s",
             marker, ids.size(), actualId, expectedId,
             (actualId == expectedId) ? "" : "  <-- mismatch");
        return actualId == expectedId ? actualId : -1;
    };

    LOGI("ChatML tokenizer self-check (Qwen2.5 expects im_start=151644, im_end=151645, endoftext=151643)");
    h.imStartId = one("<|im_start|>", 151644);
    h.imEndId   = one("<|im_end|>", 151645);
    h.eotId     = one("<|endoftext|>", 151643);
    LOGI("  ids: im_start=%d im_end=%d endoftext=%d", h.imStartId, h.imEndId, h.eotId);

    if (h.imStartId < 0 || h.imEndId < 0 || h.eotId < 0) {
        throw MnnError(ERR_LOAD,
            "tokenizer ChatML self-check failed: expected Qwen2.5 ids "
            "<|im_start|>=151644, <|im_end|>=151645, <|endoftext|>=151643; "
            "check tokenizer.txt and MNN logs");
    }
}

// 模型目录预检：把"文件缺失 / 推送不完整"这类最常见的初始化失败在进入 MNN 之前就说清楚
static void checkModelFiles(const std::string& configPath) {
    const size_t slash = configPath.find_last_of('/');
    const std::string dir = (slash == std::string::npos) ? "." : configPath.substr(0, slash);
    static const char* kFiles[] = {"config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt"};
    bool configOk = false;
    LOGI("model dir: %s", dir.c_str());
    for (const char* f : kFiles) {
        const std::string p = dir + "/" + f;
        struct stat st {};
        if (stat(p.c_str(), &st) != 0) {
            LOGW("  %-16s MISSING (errno=%d: %s)", f, errno, strerror(errno));
        } else {
            const bool readable = access(p.c_str(), R_OK) == 0;
            LOGI("  %-16s %lld bytes%s", f, (long long)st.st_size, readable ? "" : "  (NOT READABLE)");
            if (std::strcmp(f, "config.json") == 0) configOk = readable;
        }
    }
    if (!configOk)
        throw MnnError(ERR_MODEL_FILES, "config.json missing or unreadable in " + dir +
                       " (check adb push path / app-specific dir permission)");
}

static std::string buildInfo() {
    std::string s;
#if defined(__aarch64__)
    s += "abi=arm64-v8a";
#elif defined(__arm__)
    s += "abi=armeabi-v7a";
#elif defined(__x86_64__)
    s += "abi=x86_64";
#else
    s += "abi=unknown";
#endif
    s += ", api_level=" + std::to_string(__ANDROID_API__);
#ifdef MNN_VERSION
    s += std::string(", mnn=") + MNN_VERSION;
#endif
#if defined(__cpp_exceptions)
    s += ", exceptions=on";
#else
    s += ", exceptions=OFF";
#endif
    return s;
}

// =============================== 流式输出 sink ===============================
// MNN 往 std::ostream 写文本；这里实现一个 streambuf，把 UTF-8 完整的片段以 byte[] 回调给 Kotlin。
// 只在调用 nativeGenerate 的线程里使用（env 仅对该线程有效）。
class TokenSink final : public std::streambuf {
public:
    TokenSink(JNIEnv* env, jobject cb, LlmHandle& h)
        : env_(env), cb_(cb), h_(h), owner_(std::this_thread::get_id()) {}

    bool sawStop() const { return eop_; }
    const char* stopMarker() const { return stopMarker_ ? stopMarker_ : ""; }
    bool callbackFailed() const { return cbFailed_; }
    void finish() { drain(true); }

protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (n > 0) feed(s, static_cast<size_t>(n));
        return n;
    }
    int_type overflow(int_type ch) override {
        if (!traits_type::eq_int_type(ch, traits_type::eof())) {
            const char c = traits_type::to_char_type(ch);
            feed(&c, 1);
        }
        return traits_type::not_eof(ch);
    }
    int sync() override { return 0; }

private:
    void feed(const char* s, size_t n) {
        if (dead_ || eop_) return;
        pending_.append(s, n);
        drain(false);
    }
    void drain(bool final) {
        if (dead_) { pending_.clear(); return; }
        size_t limit = pending_.size();
        const char* which = nullptr;
        const size_t pos = findStopMarker(pending_, &which);
        if (pos != std::string::npos) {
            limit = pos;
            eop_ = true;
            stopMarker_ = which;
        } else if (!final) {
            limit -= partialMarkerSuffix(pending_);
        }
        limit = utf8SafeLen(pending_, limit);
        if (limit > 0) emit(pending_.data(), limit);
        if (eop_ || final) pending_.clear(); else pending_.erase(0, limit);
    }
    void emit(const char* p, size_t n) {
        if (std::this_thread::get_id() != owner_) {
            LOGE("token written from a different thread than nativeGenerate; JNIEnv is invalid there. Stop.");
            dead_ = true; h_.stop_requested.store(true); return;
        }
        jbyteArray arr = env_->NewByteArray(static_cast<jsize>(n));
        if (!arr) {
            LOGE("NewByteArray(%zu) failed (OOM)", n);
            env_->ExceptionClear();
            dead_ = true; h_.stop_requested.store(true); return;
        }
        env_->SetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte*>(p));
        jboolean goOn = env_->CallBooleanMethod(cb_, g_onToken, arr);
        env_->DeleteLocalRef(arr);
        if (env_->ExceptionCheck()) {
            LOGE("Kotlin TokenCallback threw an exception; stopping generation");
            env_->ExceptionDescribe();      // 堆栈打到 logcat
            env_->ExceptionClear();
            cbFailed_ = true; dead_ = true; goOn = JNI_FALSE;
        }
        if (!goOn) h_.stop_requested.store(true, std::memory_order_release);   // Kotlin 要求停止
    }

    JNIEnv* env_; jobject cb_; LlmHandle& h_;
    std::thread::id owner_;
    std::string pending_;
    const char* stopMarker_ = nullptr;
    bool eop_ = false, dead_ = false, cbFailed_ = false;
};

// =============================== JNI 实现 ===============================
static jlong JNICALL nativeCreate(JNIEnv* env, jclass, jstring jConfigPath) {
    return safeCall(env, "nativeCreate", [&]() -> jlong {
        const std::string configPath = toStd(env, jConfigPath);
        LOGI("create: %s", configPath.c_str());
        checkModelFiles(configPath);

        auto h = std::make_shared<LlmHandle>();
        const auto t0 = Clock::now();
        h->llm.reset(Llm::createLLM(configPath));
        if (!h->llm)
            throw MnnError(ERR_CREATE, "Llm::createLLM returned nullptr (malformed config.json? see MNNJNI logs)");

        const jlong id = g_nextId.fetch_add(1);
        { std::lock_guard<std::mutex> lk(g_regMu); g_registry.emplace(id, h); }
        LOGI("create ok: handle=%lld (%lld ms)", (long long)id, msSince(t0));
        return id;
    });
}

static void JNICALL nativeLoad(JNIEnv* env, jclass, jlong id, jstring jExtraConfig) {
    safeCall(env, "nativeLoad", [&] {
        auto h = requireHandle(id);
        const std::string cfg = toStd(env, jExtraConfig);
        std::lock_guard<std::mutex> lk(h->mu);   // 阻塞等待其它操作；load 本身不可中断
        if (h->released.load()) throw MnnError(ERR_BAD_STATE, "handle already released");
        if (h->loaded)          throw MnnError(ERR_BAD_STATE, "model already loaded");

        LOGI("load: extra_config=%s", cfg.c_str());
        const auto t0 = Clock::now();
        if (!cfg.empty() && !llm_api::callSetConfig(*h->llm, cfg, 0))
            throw MnnError(ERR_LOAD, "set_config() rejected the config: " + cfg);
        if (!llm_api::callLoad(*h->llm, 0))
            throw MnnError(ERR_LOAD, "Llm::load() returned false (see MNNJNI / MNN logs: backend init, weight file, OOM)");
        if (kPromptIsFinal) probeChatMl(*h);
        h->loaded = true;
        LOGI("load ok (%lld ms)", msSince(t0));
    });
}

static jint JNICALL nativeGenerate(JNIEnv* env, jclass, jlong id, jlong gen,
                                   jbyteArray jPrompt, jint maxNewTokens, jobject cb) {
    return safeCall(env, "nativeGenerate", [&]() -> jint {
        if (!jPrompt || !cb)    throw MnnError(ERR_BAD_ARGUMENT, "prompt/callback is null");
        if (maxNewTokens <= 0)  throw MnnError(ERR_BAD_ARGUMENT, "maxNewTokens must be > 0");

        auto h = requireHandle(id);
        std::unique_lock<std::mutex> lk(h->mu, std::try_to_lock);
        if (!lk.owns_lock())
            throw MnnError(ERR_BAD_STATE, "handle is busy (another load/generate/reset/release is running)");
        if (h->released.load()) throw MnnError(ERR_BAD_STATE, "handle already released");
        if (!h->loaded)         throw MnnError(ERR_BAD_STATE, "model not loaded");

        const std::string prompt = bytesToString(env, jPrompt);
        h->stop_requested.store(false, std::memory_order_release);
        h->active_gen.store(static_cast<uint64_t>(gen), std::memory_order_release);
        struct GenGuard { LlmHandle& h; ~GenGuard() { h.active_gen.store(0, std::memory_order_release); } } guard{*h};

        std::vector<int> ids;
        if (kPromptIsFinal) {
            // Kotlin 构建完整 ChatML，每轮从空 KV 开始，避免残留模型上下文与应用历史不一致。
            llm_api::reset(*h->llm);
            ids = llm_api::encode(*h->llm, prompt);
            if (ids.empty()) throw MnnError(ERR_BAD_ARGUMENT, "prompt tokenized to 0 ids");

            LOGI("generate: gen=%lld prompt=%zu bytes -> %zu tokens (first=%d last=%d) max_new=%d",
                 (long long)gen, prompt.size(), ids.size(), ids.front(), ids.back(), (int)maxNewTokens);
            // Debug build only: expose the initial prompt structure for ChatML diagnostics.
            LOGD("prompt dump:\n%s", prompt.substr(0, 1200).c_str());
        } else {
            LOGI("generate: gen=%lld prompt=%zu bytes max_new=%d",
                 (long long)gen, prompt.size(), (int)maxNewTokens);
        }

        const auto t0 = Clock::now();
        TokenSink sink(env, cb, *h);
        std::ostream os(&sink);
        int produced = 0;
        jint reason = REASON_EOS;
        std::string stopCause;

        // 四层结束判断：可见停止标记、MNN stoped()、最新 token id、max_new_tokens 兜底。
        auto checkEos = [&]() -> bool {
            if (sink.sawStop()) {
                stopCause = std::string("text marker ") + sink.stopMarker();
                return true;
            }
            if (llm_api::finished(*h->llm)) {
                stopCause = "MNN stoped()";
                return true;
            }
            const int token = llm_api::lastTokenId(*h->llm);
            if (h->isStopId(token)) {
                stopCause = "token id " + std::to_string(token);
                return true;
            }
            return false;
        };

        try {
            if (kPromptIsFinal) {
                llm_api::startFromIds(*h->llm, ids, &os, kEndMarker);
            } else {
                llm_api::startAndFirstToken(*h->llm, prompt, &os, kEndMarker);
            }
            produced = 1;

            for (;;) {
                if (h->shouldStop())          { reason = REASON_STOPPED;    break; }
                if (checkEos())               { reason = REASON_EOS;        break; }
                if (produced >= maxNewTokens) { reason = REASON_MAX_TOKENS; break; }
                llm_api::step(*h->llm);
                ++produced;
            }
            os.flush();
            sink.finish();
        } catch (...) {
            LOGE("generate aborted by exception after %d tokens; resetting context", produced);
            resetQuietly(*h);
            throw;
        }

        const long long ms = msSince(t0);
        if (reason == REASON_EOS) {
            LOGI("EOS detected by [%s] after %d tokens", stopCause.c_str(), produced);
        } else if (reason == REASON_MAX_TOKENS) {
            LOGW("hit max_new_tokens=%d with NO stop signal from any layer: model did not emit a recognized stop token",
                 (int)maxNewTokens);
        }
        LOGI("generate end: reason=%d tokens=%d time=%lldms (%.1f tok/s)", (int)reason, produced, ms,
             ms > 0 ? produced * 1000.0 / (double)ms : 0.0);

        if (reason != REASON_EOS && kResetOnAbnormalEnd) resetQuietly(*h);
        if (sink.callbackFailed()) throw MnnError(ERR_CALLBACK, "Kotlin TokenCallback threw; generation stopped");
        return reason;
    });
}
// 不取 mu（生成期间 mu 被占用），只翻原子标志；对已释放句柄幂等
static void JNICALL nativeStop(JNIEnv* env, jclass, jlong id, jlong gen) {
    safeCall(env, "nativeStop", [&] {
        auto h = findHandle(id);
        if (!h) { LOGD("stop: handle %lld already released", (long long)id); return; }
        const uint64_t active = h->active_gen.load(std::memory_order_acquire);
        if (gen <= 0 || static_cast<uint64_t>(gen) == active) {   // gen<=0 表示"无论哪一代"
            h->stop_requested.store(true, std::memory_order_release);
            LOGI("stop requested (handle=%lld gen=%lld active=%llu)", (long long)id, (long long)gen, (unsigned long long)active);
        } else {
            LOGD("ignore stale stop (gen=%lld, active=%llu)", (long long)gen, (unsigned long long)active);
        }
    });
}

static void JNICALL nativeReset(JNIEnv* env, jclass, jlong id) {
    safeCall(env, "nativeReset", [&] {
        auto h = requireHandle(id);
        std::unique_lock<std::mutex> lk(h->mu, std::try_to_lock);
        if (!lk.owns_lock()) throw MnnError(ERR_BAD_STATE, "handle is busy");
        if (h->released.load() || !h->loaded) throw MnnError(ERR_BAD_STATE, "handle released or model not loaded");
        llm_api::reset(*h->llm);
        LOGD("context reset");
    });
}

static void JNICALL nativeRelease(JNIEnv* env, jclass, jlong id) {
    safeCall(env, "nativeRelease", [&] {
        std::shared_ptr<LlmHandle> h;
        {   // 先摘出注册表：此后新的 JNI 调用都会得到 INVALID_HANDLE
            std::lock_guard<std::mutex> lk(g_regMu);
            auto it = g_registry.find(id);
            if (it == g_registry.end()) { LOGD("release: handle %lld already gone", (long long)id); return; }
            h = std::move(it->second);
            g_registry.erase(it);
        }
        h->released.store(true, std::memory_order_release);        // 在途推理会在下一个 token 检查点退出
        h->stop_requested.store(true, std::memory_order_release);

        const auto t0 = Clock::now();
        std::lock_guard<std::mutex> lk(h->mu);                     // 等待在途 load/generate 彻底退出
        const long long waited = msSince(t0);
        if (waited > 200) LOGW("release waited %lld ms for an in-flight operation", waited);
        h->llm.reset();                                            // 在锁内析构
        LOGI("release ok: handle=%lld (total wait+destroy %lld ms)", (long long)id, msSince(t0));
    });
}

static void JNICALL nativeSetVerbose(JNIEnv*, jclass, jboolean on) { g_verbose.store(on == JNI_TRUE); }

static jstring JNICALL nativeBuildInfo(JNIEnv* env, jclass) {
    const std::string s = buildInfo();
    return env->NewStringUTF(s.c_str());   // 纯 ASCII
}

// =============================== JNI_OnLoad ===============================
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    std::set_terminate(terminateHandler);
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }
    LOGI("JNI_OnLoad: %s", buildInfo().c_str());

    auto fail = [&](const char* what, const char* name) {
        LOGE("JNI_OnLoad: %s not found: %s  (class renamed by R8/ProGuard? package mismatch?)", what, name);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_ERR;
    };

    jclass ex = env->FindClass(kExceptionClass);
    if (!ex) return fail("class", kExceptionClass);
    g_exceptionClass = static_cast<jclass>(env->NewGlobalRef(ex));
    g_exceptionCtor  = env->GetMethodID(ex, "<init>", "(Ljava/lang/String;I)V");
    if (!g_exceptionCtor) return fail("constructor (String,int) of", kExceptionClass);

    jclass cb = env->FindClass(kCallbackClass);
    if (!cb) return fail("class", kCallbackClass);
    g_onToken = env->GetMethodID(cb, "onToken", "([B)Z");
    if (!g_onToken) return fail("method onToken([B)Z of", kCallbackClass);

    jclass self = env->FindClass(kNativeClass);
    if (!self) return fail("class", kNativeClass);

    static const JNINativeMethod kMethods[] = {
        {"nativeCreate",     "(Ljava/lang/String;)J",                          (void*)nativeCreate},
        {"nativeLoad",       "(JLjava/lang/String;)V",                         (void*)nativeLoad},
        {"nativeGenerate",   "(JJ[BILcom/seankew/localai/TokenCallback;)I",    (void*)nativeGenerate},
        {"nativeStop",       "(JJ)V",                                          (void*)nativeStop},
        {"nativeReset",      "(J)V",                                           (void*)nativeReset},
        {"nativeRelease",    "(J)V",                                           (void*)nativeRelease},
        {"nativeSetVerbose", "(Z)V",                                           (void*)nativeSetVerbose},
        {"nativeBuildInfo",  "()Ljava/lang/String;",                           (void*)nativeBuildInfo},
    };
    if (env->RegisterNatives(self, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
        LOGE("JNI_OnLoad: RegisterNatives failed (signature mismatch with MnnNative.kt?)");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
