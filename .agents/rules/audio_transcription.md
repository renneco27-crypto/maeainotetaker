# Audio Capture & Transcription Standards

- **Hardware Noise Suppression**: Always configure AudioRecord with MediaRecorder.AudioSource.VOICE_RECOGNITION and activate android.media.audiofx.NoiseSuppressor and AutomaticGainControl when available.
- **Voice Activity Detection**: Use Silero VAD ONNX model on-device for fast, lightweight voice segmentation.
- **Whisper STT Backend**: Use Hugging Face's Serverless Router (https://router.huggingface.co/hf-inference/models/openai/whisper-large-v3-turbo) with direct audio/wav HTTP POST requests for instant transcription without ZeroGPU quota exhaustion.
