# Assets Directory

Place the following model files in this directory:

## Silero VAD Model (Required)
- **File**: `silero_vad.onnx` (~2MB)
- **Download**: https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx
- **Purpose**: Voice Activity Detection for speech segmentation

## Whisper Model (Required for Sideload APK)
- **File**: `whisper/ggml-base.bin` (~148MB)
- **Download**: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin
- **Purpose**: Speech-to-text transcription (multilingual)
- **Note**: For Play Store distribution, use the `whisperModelPack` asset pack instead

## Directory Structure
```
assets/
├── silero_vad.onnx
└── whisper/
    └── ggml-base.bin   (only for sideload APK)
```