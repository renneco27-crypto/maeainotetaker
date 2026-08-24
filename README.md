# LecturePal — Offline Android Student Note-Taking App

A free, fully offline Android note-taking application for students. It captures lecture audio, performs local speech-to-text using whisper.cpp, segments audio using Silero VAD, and stores all data locally using Room/SQLite. No cloud, no subscriptions, no internet required for core functionality.

## Features

- **Offline Speech-to-Text**: Uses whisper.cpp (ggml-base multilingual model) for high-quality transcription
- **Voice Activity Detection**: Silero VAD via ONNX Runtime segments audio intelligently
- **Live Transcription**: Real-time transcript display during recording
- **Audio Playback**: Media3 ExoPlayer with timestamp seeking
- **Local Storage**: Room/SQLite database for notes and segments
- **Multi-language**: English, Filipino/Tagalog, and Taglish code-switching support
- **Confidence Scoring**: Low-confidence segments marked as `[unclear]`
- **Foreground Service**: Recording continues in background with notification controls

## Architecture

```
AudioRecord (16kHz PCM) → Silero VAD → whisper.cpp → Room Database
                                    ↓
                              MediaRecorder (AAC/M4A file)
```

## Requirements

- Android API 28+ (Android 9.0 Pie)
- NDK 26.1+
- ~150MB storage for Whisper model

## Building

### Prerequisites

1. Android Studio Koala or later
2. NDK 26.1.10909125 (installed via SDK Manager)
3. CMake 3.22.1+

### Model Setup

The Whisper model (`ggml-base.bin`, ~148MB) must be placed in:
- **Play Store (AAB)**: `whisperModelPack/src/main/assets/whisper/ggml-base.bin`
- **Sideload APK**: `app/src/main/assets/whisper/ggml-base.bin`

Download from: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin

### Silero VAD Model

Place `silero_vad.onnx` in `app/src/main/assets/`

Download from: https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires keystore)
./gradlew assembleRelease

# Run tests
./gradlew test
./gradlew connectedAndroidTest
```

## Project Structure

```
com.cortesnotetaker.app/
├── app/
│   ├── src/main/
│   │   ├── assets/                          # Silero VAD ONNX model
│   │   ├── cpp/                             # whisper.cpp NDK bridge
│   │   │   ├── CMakeLists.txt
│   │   │   ├── whisper_jni.cpp              # JNI bridge
│   │   │   └── whisper.cpp/                 # whisper.cpp v1.9.1 source
│   │   ├── java/com/cortesnotetaker/app/
│   │   │   ├── LecturePalApp.kt             # Application class, Koin init
│   │   │   ├── MainActivity.kt              # Single Activity
│   │   │   ├── di/                          # Koin modules
│   │   │   ├── data/                        # Room database, repositories
│   │   │   ├── audio/                       # Audio capture, recording, playback
│   │   │   ├── vad/                         # Silero VAD detector
│   │   │   ├── stt/                         # Whisper engine wrapper
│   │   │   ├── service/                     # Foreground recording service
│   │   │   └── ui/                          # Compose screens
```

## Dependencies

| Component | Version | License |
|-----------|---------|---------|
| whisper.cpp | v1.9.1 | MIT |
| Silero VAD | v6.2.1 | MIT |
| ONNX Runtime Android | 1.29.0 | MIT |
| Room | 2.8.4 | Apache 2.0 |
| Media3 ExoPlayer | 1.10.1 | Apache 2.0 |
| Koin | 4.2.2 | Apache 2.0 |
| Compose BOM | 2026.08.00 | Apache 2.0 |

All licenses permit free use, modification, and redistribution without fee.

## Roadmap

### Phase 1 (Current) — Core Pipeline
- AudioRecord → Silero VAD → whisper.cpp → Live Transcript → Room → Playback

### Phase 2 — Speaker Identification
- Embedding-based speaker diarization
- Voice profile persistence
- Speaker renaming

### Phase 3 — Advanced UX
- Custom vocabulary/glossary editor
- Subject-aware formatting hints
- Export (PDF/TXT/SRT)

## License

This project is licensed under the MIT License - see the LICENSE file for details.

Third-party licenses:
- whisper.cpp: MIT
- Silero VAD: MIT
- ONNX Runtime: MIT
- AndroidX libraries: Apache 2.0