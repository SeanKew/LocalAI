# LocalAI Android

Starter project for on-device Qwen2.5-1.5B-Instruct inference using Alibaba MNN, Kotlin, JNI, and C++17. Target ABI: arm64-v8a.

## Native dependency setup

Run PowerShell from the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\fetch_mnn_deps.ps1
```

The script downloads the arm64-v8a `libMNN.so` from the official MNNChat Android APK and prepares the matching public MNN header layout under `app/src/main/cpp/include/`. MNNChat's Android package bundles the LLM runtime into `libMNN.so`; it does not use a separate `libllm.so`. The script reports the source URLs and fails rather than silently leaving incomplete dependencies.

Verify the dependency provenance/version before shipping. The binary is a prebuilt from MNNChat and should be treated as a versioned native dependency. The script does not download model weights.

Directory map:
- `app/src/main/cpp/`: JNI bridge, CMake, `include/`, optional `lib/`
- `app/src/main/jniLibs/arm64-v8a/`: tracked ARM64 shared libraries
- `app/src/main/java/com/seankew/localai/MnnLlm.kt`: Kotlin wrapper

## Model

Place exported model files at `context.getExternalFilesDir(null)/models/qwen2.5-1.5b-mnn/`; `config.json` is expected. Model weights are intentionally not committed. A 1.5B quantized model can still require substantial memory.

## Status and limitations

This scaffold has not yet been built with Android SDK/NDK or tested on a device. The native stop method is only a cancellation request until a version-specific incremental generation loop is implemented and tested; native release must also be synchronized with active inference.

References:
- Official MNN repository: https://github.com/alibaba/MNN
- Official Android LLM chat app: https://github.com/alibaba/MNN/tree/master/apps/Android/MnnLlmChat
- LLM export docs: https://github.com/alibaba/MNN/tree/master/transformers/llm/export
