cmake_minimum_required(VERSION 3.22.1)
project("whisper_jni" LANGUAGES C CXX)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# whisper.cpp as subdirectory
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP ON CACHE BOOL "" FORCE)

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

if(${CMAKE_ANDROID_ARCH_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math -march=armv8.2-a+fp16 -fno-math-errno -funsafe-math-optimizations")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -march=armv8.2-a+fp16 -fno-math-errno -funsafe-math-optimizations")
endif()

target_compile_options(whisper PRIVATE -O3 -ffast-math -Wno-unused-parameter -Wno-sign-compare -Wno-unused-variable)
target_compile_options(whisper_jni PRIVATE -O3 -ffast-math)