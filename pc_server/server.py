import io
import time
from fastapi import FastAPI, Request, HTTPException, UploadFile, File
from fastapi.responses import JSONResponse
from faster_whisper import WhisperModel

from collections import deque
import language_tool_python

app = FastAPI(title="Local PC Whisper Server")

# Rolling memory of the last few transcribed segments to provide context to Whisper
recent_context = deque(maxlen=5)

# Initialize the grammar and context correction tool
print("Loading LanguageTool for contextual correction...")
lang_tool = language_tool_python.LanguageTool('en-US')

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
        
        # Build the context prompt from recent history
        context_prompt = " ".join(recent_context) if recent_context else None
        
        # Transcribe (auto-detect language) using past context
        segments, info = model.transcribe(audio_io, beam_size=5, initial_prompt=context_prompt)
        
        raw_text = " ".join([segment.text for segment in segments]).strip()
        
        # Post-process: Correct contextual spelling and grammar mistakes
        corrected_text = lang_tool.correct(raw_text) if raw_text else ""
        
        # Add the new transcribed text to our rolling memory context
        if corrected_text:
            recent_context.append(corrected_text)
            
        print(f"Transcribed in {time.time() - start_time:.2f}s:")
        print(f"  Raw: {raw_text}")
        print(f"  Fixed: {corrected_text}")
        
        return JSONResponse({"text": corrected_text})
        
    except Exception as e:
        print(f"Error during transcription: {e}")
        return JSONResponse({"error": str(e)}, status_code=500)

if __name__ == "__main__":
    import uvicorn
    # Listen on all interfaces so the phone can connect
    uvicorn.run("server:app", host="0.0.0.0", port=8000, reload=True)
