# Optional native library staging

This directory is reserved for project-owned or locally built native libraries that are not Android ABI-packaged shared libraries. Runtime `.so` files for the Android app belong under `app/src/main/jniLibs/arm64-v8a/`.

Do not copy a second or mismatched MNN runtime here.
