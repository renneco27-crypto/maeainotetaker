# LecturePal — AI-Powered Smart Lecture Note-Taking App for Android 🎙️📚

**LecturePal** is a high-performance Android note-taking application designed for students and professionals. It captures lectures, filters background noise, performs intelligent Voice Activity Detection (VAD) locally on-device, and streams live speech-to-text with state-of-the-art accuracy using **Whisper Large V3 Turbo**.

---

## 🌟 Key Features

- **⚡ Instant Real-Time Transcription**: High-accuracy speech-to-text powered by Whisper Large V3 Turbo via Hugging Face Serverless Inference.
- **🧠 Zero-Latency On-Device VAD**: Uses **Silero VAD v6** running locally via ONNX Runtime to detect natural speech boundaries in real-time.
- **🎧 Bluetooth & External Mic Support**: Automatic audio routing for Bluetooth headsets, wireless lapel microphones (SCO / BLE), USB-C mics, and wired headsets.
- **🔇 Hardware Noise Suppression**: Activates Android DSP hardware beamforming, `NoiseSuppressor`, and `AutomaticGainControl` for clean audio capture in noisy lecture halls.
- **🤖 One-Tap "Copy to Claude"**: Formats the transcript with an AI study prompt and launches [Claude.ai](https://claude.ai/chat/) for instant summaries, study flashcards, and structured notes.
- **📋 "Copy All"**: Quick one-tap clipboard copy for transcripts and lecture segments.
- **🎵 Synchronized Audio Playback**: Built-in Media3 ExoPlayer with timestamp scrubbing and clickable transcript segments.
- **💾 100% Local Note Database**: Notes, subjects, timestamps, and transcripts are saved securely offline in Room/SQLite.
- **🔔 Background Foreground Service**: Continues recording reliably with notification controls even when the screen is off.

---

## 🏗️ Architecture & Pipeline

```mermaid
graph TD
    A[Microphone / Bluetooth Mic] -->|VOICE_RECOGNITION| B[AudioCaptureManager]
    B -->|Hardware NoiseSuppressor & AGC| C[16kHz PCM Stream]
    C -->|Real-Time Inference| D[Silero VAD (ONNX Runtime)]
    D -->|Speech Segment Complete| E[NetworkWhisperClient]
    E -->|HTTPS audio/wav| F[Whisper Large V3 Turbo Serverless API]
    F -->|Instant Transcript| G[RecordingViewModel / Live UI]
    G -->|Save Note & Segments| H[Room Database / SQLite]
    H -->|Playback & Review| I[Media3 ExoPlayer & Claude AI Export]
```

---

## 📁 Project Structure

```
com.cortesnotetaker.app/
├── app/src/main/
│   ├── assets/
│   │   └── silero_vad.onnx             # Silero VAD ONNX model for on-device detection
│   ├── java/com/cortesnotetaker/app/
│   │   ├── LecturePalApp.kt            # App entry point & DI setup
│   │   ├── MainActivity.kt             # Jetpack Compose Host Activity
│   │   ├── audio/                      # Audio recording, playback, Bluetooth & Noise Suppression
│   │   │   ├── AudioCaptureManager.kt  # Mic capture, Bluetooth SCO/BLE routing, DSP filters
│   │   │   └── AudioPlaybackManager.kt # Media3 ExoPlayer integration
│   │   ├── vad/                        # Voice Activity Detection
│   │   │   └── SileroVadDetector.kt    # On-device Silero VAD via ONNX Runtime
│   │   ├── stt/                        # Speech-to-Text
│   │   │   └── NetworkWhisperClient.kt # Serverless Whisper Large V3 Turbo client
│   │   ├── data/                       # Persistence Layer
│   │   │   ├── db/                     # Room Database, DAOs, Entities
│   │   │   └── repository/             # Note & Segment Repositories
│   │   ├── service/
│   │   │   └── RecordingService.kt     # Foreground Recording Service
│   │   └── ui/                         # Jetpack Compose Material 3 UI
│   │       ├── notelist/               # Note library & search
│   │       ├── notedetail/             # Audio player, transcript viewer, Claude AI export
│   │       └── recording/              # Live recording screen with real-time text stream
```

---

## 🚀 Getting Started & Configuration

### Prerequisites
- Android Studio Koala+ or CLI SDK
- Android Device running Android 9.0+ (API 28+)
- A free [Hugging Face Token](https://huggingface.co/settings/tokens)

### Setup
1. Clone the repository:
   ```bash
   git clone https://github.com/renneco27-crypto/maeainotetaker.git
   cd "note taker"
   ```

2. Add your Hugging Face Access Token in `local.properties`:
   ```properties
   HF_TOKEN=hf_YourTokenHere...
   ```

3. Build and install on your device:
   ```powershell
   .\gradlew installDebug
   ```

---

## 📄 License
This project is licensed under the MIT License.