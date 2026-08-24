# whisper.cpp Submodule Setup

This directory should contain the whisper.cpp source code (v1.9.1).

## Setup Instructions

```bash
# Clone whisper.cpp v1.9.1 as a submodule
git submodule add -b v1.9.1 https://github.com/ggerganov/whisper.cpp.git app/src/main/cpp/whisper.cpp

# Or manually download and extract:
# 1. Download from https://github.com/ggerganov/whisper.cpp/archive/refs/tags/v1.9.1.tar.gz
# 2. Extract to this directory
```

## Required Files

After setup, this directory should contain:
- `CMakeLists.txt` (from whisper.cpp)
- `include/whisper.h`
- `src/*.c` and `src/*.h` files
- `ggml/` subdirectory with ggml source
- `ggml/src/ggml-cpu/` for CPU optimizations

## Model File

The Whisper model file (`ggml-base.bin`, ~148MB) is NOT included in this repository.

Download from: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin

Place it in:
- **Play Store (AAB)**: `whisperModelPack/src/main/assets/whisper/ggml-base.bin`
- **Sideload APK**: `app/src/main/assets/whisper/ggml-base.bin`

## Build Requirements

- NDK 26.1+
- CMake 3.22.1+
- C++17 support

The CMakeLists.txt in the parent directory (`app/src/main/cpp/CMakeLists.txt`) includes this as a subdirectory.