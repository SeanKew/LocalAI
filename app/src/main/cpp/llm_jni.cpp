#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <memory>
#include <sstream>
#include <string>
#include "llm/llm.hpp"

namespace {
constexpr const char* TAG = "LocalAI";
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlmHandle {
    std::unique_ptr<MNN::Transformer::Llm> llm;
    std::atomic<bool> stop_requested{false};
};

size_t completeUtf8Prefix(const std::string& text) {
    const size_t n = text.size();
    if (n == 0) return 0;
    size_t leadPos = n;
    while (leadPos > 0 && (static_cast<unsigned char>(text[leadPos - 1]) & 0xC0) == 0x80) --leadPos;
    if (leadPos == n) return n;
    if (leadPos == 0) return 0;
    const unsigned char lead = static_cast<unsigned char>(text[leadPos - 1]);
    const size_t need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    return n - (leadPos - 1) >= need ? n : leadPos - 1;
}

class CallbackStreamBuf final : public std::stringbuf {
public:
    CallbackStreamBuf(JNIEnv* env, jobject callback, LlmHandle* handle)
        : env_(env), callback_(callback), handle_(handle) {
        jclass cls = env_->GetObjectClass(callback_);
        method_ = env_->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
        env_->DeleteLocalRef(cls);
    }
    int sync() override {
        pending_ += str();
        str("");
        const size_t count = completeUtf8Prefix(pending_);
        if (count == 0) return 0;
        const std::string chunk = pending_.substr(0, count);
        pending_.erase(0, count);
        jstring value = env_->NewStringUTF(chunk.c_str());
        if (!value) return -1;
        const jboolean keep = env_->CallBooleanMethod(callback_, method_, value);
        env_->DeleteLocalRef(value);
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            handle_->stop_requested.store(true);
            return -1;
        }
        if (keep != JNI_TRUE) handle_->stop_requested.store(true);
        return 0;
    }
private:
    JNIEnv* env_;
    jobject callback_;
    jmethodID method_{};
    LlmHandle* handle_;
    std::string pending_;
};
}

extern "C" {
JNIEXPORT jlong JNICALL
Java_com_seankew_localai_MnnLlm_nativeCreate(JNIEnv* env, jobject, jstring configPath) {
    if (!configPath) return 0;
    const char* path = env->GetStringUTFChars(configPath, nullptr);
    if (!path) return 0;
    auto handle = std::make_unique<LlmHandle>();
    handle->llm.reset(MNN::Transformer::Llm::createLLM(path));
    env->ReleaseStringUTFChars(configPath, path);
    if (!handle->llm) {
        LOGE("Llm::createLLM failed");
        return 0;
    }
    return reinterpret_cast<jlong>(handle.release());
}

JNIEXPORT jboolean JNICALL
Java_com_seankew_localai_MnnLlm_nativeLoad(JNIEnv* env, jobject, jlong ptr, jstring extraConfig) {
    auto* h = reinterpret_cast<LlmHandle*>(ptr);
    if (!h || !h->llm || !extraConfig) return JNI_FALSE;
    const char* cfg = env->GetStringUTFChars(extraConfig, nullptr);
    if (!cfg) return JNI_FALSE;
    const bool configured = h->llm->set_config(cfg);
    env->ReleaseStringUTFChars(extraConfig, cfg);
    return configured && h->llm->load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_seankew_localai_MnnLlm_nativeGenerate(JNIEnv* env, jobject, jlong ptr, jstring prompt,
                                                jint maxTokens, jobject callback) {
    auto* h = reinterpret_cast<LlmHandle*>(ptr);
    if (!h || !h->llm || !prompt || !callback) return;
    h->stop_requested.store(false);
    const char* p = env->GetStringUTFChars(prompt, nullptr);
    if (!p) return;
    const std::string input(p);
    env->ReleaseStringUTFChars(prompt, p);
    CallbackStreamBuf buffer(env, callback, h);
    std::ostream output(&buffer);
    try {
        h->llm->response(input, &output, nullptr, static_cast<int>(maxTokens));
        output.flush();
    } catch (...) {
        LOGE("Exception escaped MNN response()");
    }
}

JNIEXPORT void JNICALL
Java_com_seankew_localai_MnnLlm_nativeStop(JNIEnv*, jobject, jlong ptr) {
    auto* h = reinterpret_cast<LlmHandle*>(ptr);
    if (h) h->stop_requested.store(true);
}
JNIEXPORT void JNICALL
Java_com_seankew_localai_MnnLlm_nativeReset(JNIEnv*, jobject, jlong ptr) {
    auto* h = reinterpret_cast<LlmHandle*>(ptr);
    if (h && h->llm) h->llm->reset();
}
JNIEXPORT void JNICALL
Java_com_seankew_localai_MnnLlm_nativeRelease(JNIEnv*, jobject, jlong ptr) {
    delete reinterpret_cast<LlmHandle*>(ptr);
}
}
