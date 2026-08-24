# Whisper Model Asset Pack

This is a Play Asset Delivery module for the Whisper model (`ggml-base.bin`).

## Setup

Place the model file at:
```
whisperModelPack/src/main/assets/whisper/ggml-base.bin
```

Download from: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin

## Configuration

- **Delivery Type**: Install-time (downloaded with app install)
- **Pack Name**: `whisperModelPack`
- **Max Size**: 1 GB (well within limits)

## Usage in App

The app checks for this asset pack first. If available (Play Store install), it uses the asset pack path. Otherwise, it falls back to the `app/src/main/assets/whisper/ggml-base.bin` path for sideload APKs.

## Building

```bash
# Build the asset pack
./gradlew :whisperModelPack:assembleDebug

# The asset pack will be included in the AAB when building the app bundle
./gradlew bundleRelease
```