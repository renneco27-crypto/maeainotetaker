import gradio as gr
import base64
import tempfile
import os
from faster_whisper import WhisperModel
import spaces

print("Loading Whisper model...")
model = WhisperModel("tiny.en", device="cpu", compute_type="int8")
print("Model loaded.")

@spaces.GPU(duration=60)
def transcribe_base64(b64_audio):
    audio_data = base64.b64decode(b64_audio)
    with tempfile.NamedTemporaryFile(delete=False, suffix=".wav") as tmp:
        tmp.write(audio_data)
        tmp_path = tmp.name
        
    try:
        segments, _ = model.transcribe(tmp_path, language="en", beam_size=1)
        text = " ".join([segment.text for segment in segments])
        return text.strip()
    finally:
        os.remove(tmp_path)

# Standard Gradio Interface that Hugging Face guarantees to detect!
demo = gr.Interface(
    fn=transcribe_base64,
    inputs=gr.Textbox(label="Base64 Audio"),
    outputs=gr.Textbox(label="Transcript"),
    api_name="transcribe"
)

demo.launch()
