# arm64-v8a native libraries

Run `powershell -ExecutionPolicy Bypass -File scripts\fetch_mnn_deps.ps1` from the repository root to fetch `libMNN.so` and its provenance note.

The selected MNNChat prebuilt includes the LLM runtime in `libMNN.so`; this setup therefore does not expect a separate `libllm.so`. Native binaries are not ignored by `.gitignore`. The dependency-fetch script prepares local files but does not upload/commit binary artifacts automatically.
