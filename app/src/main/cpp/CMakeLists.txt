cmake_minimum_required(VERSION 3.22.1)
project(localai_jni LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MNN_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(MNN_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")
foreach(required_file "${MNN_LIB_DIR}/libMNN.so" "${MNN_INCLUDE_DIR}/llm/llm.hpp" "${MNN_INCLUDE_DIR}/MNN/MNNDefine.h")
    if(NOT EXISTS "${required_file}")
        message(FATAL_ERROR "Missing MNN dependency: ${required_file}. Run scripts/fetch_mnn_deps.ps1 and see README.md.")
    endif()
endforeach()

# The official MNNChat Android prebuilt bundles the LLM runtime in libMNN.so.
# It does not provide a separate libllm.so, so do not link/load one.
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${MNN_LIB_DIR}/libMNN.so")

add_library(mnnllm_jni SHARED llm_jni.cpp)
target_include_directories(mnnllm_jni PRIVATE "${MNN_INCLUDE_DIR}")
target_link_libraries(mnnllm_jni PRIVATE MNN log android)
