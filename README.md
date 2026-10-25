# LocalAI Android

Starter project for on-device Qwen2.5-1.5B-Instruct inference using Alibaba MNN, Kotlin, JNI, and C++17. Target ABI: arm64-v8a.

Status: scaffold setup in progress. This repository does not contain compiled MNN shared libraries or model weights, and an Android build/device inference has not yet been verified.

See the official MNN Android example: https://github.com/alibaba/MNN/tree/master/apps/Android/MnnLlmChat

Model export documentation: https://github.com/alibaba/MNN/tree/master/transformers/llm/export

Build MNN for Android arm64-v8a with LLM enabled. Copy matching libMNN.so, libllm.so and any dependent shared libraries to app/src/main/jniLibs/arm64-v8a/. Copy matching headers to app/src/main/cpp/include, including llm/llm.hpp and its dependencies. Never mix headers and libraries from different MNN revisions.

Place exported model files at context.getExternalFilesDir(null)/models/qwen2.5-1.5b-mnn/; config.json is expected. A 1.5B 4-bit model may require substantial memory. The stop method must be treated as a cancellation request until a version-specific incremental generation loop is implemented and tested.
