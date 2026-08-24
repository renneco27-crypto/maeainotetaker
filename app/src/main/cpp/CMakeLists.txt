cmake_minimum_required(VERSION 3.22.1)
project("whisper_jni" LANGUAGES C CXX)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# whisper.cpp as subdirectory
add_subdirectory(whisper.cpp)

# JNI bridge shared library
add_library(whisper_jni SHARED whisper_jni.cpp)

# Link against whisper.cpp and Android log
target_link_libraries(whisper_jni PRIVATE whisper android log)

# Include directories for whisper.cpp
target_include_directories(whisper_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/ggml/include
)

# ARM NEON optimizations for mid-range phones
target_compile_options(whisper PRIVATE -O3 -DGGML_USE_ACCELERATE)
target_compile_options(whisper_jni PRIVATE -O3 -DGGML_USE_ACCELERATE)

# Disable warnings for whisper.cpp
target_compile_options(whisper PRIVATE -Wno-unused-parameter -Wno-sign-compare -Wno-unused-variable)