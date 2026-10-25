# MNN headers

Run `powershell -ExecutionPolicy Bypass -File scripts\fetch_mnn_deps.ps1` from the repository root to populate `MNN/` and `llm/`.

The JNI build expects `MNN/MNNDefine.h` and `llm/llm.hpp`. Keep these headers aligned with the native runtime version; do not mix versions casually.
