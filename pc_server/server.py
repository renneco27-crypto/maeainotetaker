import io
import time
from fastapi import FastAPI, Request, HTTPException, UploadFile, File
from fastapi.responses import JSONResponse
from faster_whisper import WhisperModel

from collections import deque

app = FastAPI(title="Local PC Whisper Server")

# Rolling memory of the last few transcribed segments to provide context to Whisper
recent_context = deque(maxlen=5)

# Load model on startup
print("Loading faster-whisper model (small)...")
# 'cuda' will use GPU if available, fallback to 'cpu' if not
try:
    model = WhisperModel("small", device="cuda", compute_type="float16")
    print("Loaded on CUDA (GPU)")
except Exception as e:
    print(f"CUDA failed, falling back to CPU: {e}")
    model = WhisperModel("small", device="cpu", compute_type="int8")
    print("Loaded on CPU")

@app.post("/transcribe")
async def transcribe(request: Request):
    try:
        # The Android app sends raw audio/wav in the request body
        audio_bytes = await request.body()
        if not audio_bytes:
            raise HTTPException(status_code=400, detail="No audio data provided")
            
        # We need to wrap it in a BytesIO so faster-whisper can read it
        audio_io = io.BytesIO(audio_bytes)
        
        start_time = time.time()
        
        try:
            # Try to read the WAV file and apply spectral noise reduction
            import soundfile as sf
            import noisereduce as nr
            import numpy as np
            
            # Read audio data from the BytesIO object
            audio_io.seek(0)
            data, rate = sf.read(audio_io)
            
            # Apply noise reduction (spectral gating) to remove fans/hiss/room noise
            reduced_noise = nr.reduce_noise(y=data, sr=rate, prop_decrease=0.8)
            
            # Write the cleaned audio back to a new BytesIO object
            clean_audio_io = io.BytesIO()
            sf.write(clean_audio_io, reduced_noise, rate, format='WAV')
            clean_audio_io.seek(0)
            audio_to_transcribe = clean_audio_io
        except Exception as e:
            print(f"Noise reduction skipped: {e}")
            audio_io.seek(0)
            audio_to_transcribe = audio_io
        
        # Build the context prompt from recent history
        context_prompt = "This is a lecture in Tagalog (Filipino), Cebuano (Bisaya), and English. "
        if recent_context:
            context_prompt += " ".join(recent_context)
        
        # Transcribe using anti-hallucination parameters
        segments, info = model.transcribe(
            audio_to_transcribe, 
            beam_size=5, 
            initial_prompt=context_prompt
        )
        
        raw_text = " ".join([segment.text for segment in segments]).strip()
        
        # Add the new transcribed text to our rolling memory context
        if raw_text:
            recent_context.append(raw_text)
            
        print(f"Transcribed in {time.time() - start_time:.2f}s:")
        print(f"  Raw: {raw_text}")
        
        return JSONResponse({"text": raw_text})
        
    except Exception as e:
        print(f"Error during transcription: {e}")
        return JSONResponse({"error": str(e)}, status_code=500)

if __name__ == "__main__":
    import uvicorn
    # Listen on all interfaces so the phone can connect
    uvicorn.run("server:app", host="0.0.0.0", port=8000, reload=True)
