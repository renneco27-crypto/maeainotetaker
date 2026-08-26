import io
import time
import uuid
import subprocess
import asyncio
import tempfile
import os
from fastapi import FastAPI, Request, HTTPException, UploadFile, File
from fastapi.responses import JSONResponse
from faster_whisper import WhisperModel

from collections import deque

app = FastAPI(title="Local PC Whisper Server")

# Rolling memory of the last few transcribed segments to provide context to Whisper
recent_context = deque(maxlen=5)

# Job queue for large file uploads
jobs = {}

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

@app.post("/transcribe_file")
async def transcribe_file(file: UploadFile = File(...)):
    job_id = str(uuid.uuid4())
    jobs[job_id] = {"status": "processing", "text": None, "error": None}
    
    # Save uploaded file to memory
    content = await file.read()
    
    # Spawn background task
    asyncio.create_task(process_job(job_id, content))
    return JSONResponse({"job_id": job_id})

async def process_job(job_id: str, content: bytes):
    tmp_in_path = None
    tmp_out_path = None
    try:
        # Use a thread to avoid blocking the asyncio event loop for file I/O
        def run_processing():
            nonlocal tmp_in_path, tmp_out_path
            # Create temp files
            with tempfile.NamedTemporaryFile(delete=False, suffix=".media") as tmp_in:
                tmp_in.write(content)
                tmp_in_path = tmp_in.name
                
            with tempfile.NamedTemporaryFile(delete=False, suffix=".wav") as tmp_out:
                tmp_out_path = tmp_out.name
                
            print(f"Job {job_id}: Processing {len(content)} bytes with 1.5x FFmpeg speedup...")
            # Run ffmpeg to speed up 1.5x and convert to 16kHz WAV
            cmd = [
                "ffmpeg", "-y", "-i", tmp_in_path, 
                "-filter:a", "atempo=1.5", 
                "-ar", "16000", "-ac", "1", 
                tmp_out_path
            ]
            subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            
            print(f"Job {job_id}: FFmpeg complete. Starting Whisper inference...")
            # Transcribe
            segments, info = model.transcribe(
                tmp_out_path, 
                beam_size=5,
                initial_prompt="This is a lecture in Tagalog (Filipino), Cebuano (Bisaya), and English. "
            )
            return " ".join([segment.text for segment in segments]).strip()

        # Run the heavy processing in a thread pool
        raw_text = await asyncio.to_thread(run_processing)
        
        print(f"Job {job_id}: Completed successfully!")
        jobs[job_id]["status"] = "completed"
        jobs[job_id]["text"] = raw_text
        
    except Exception as e:
        print(f"Job {job_id} Error: {e}")
        jobs[job_id]["status"] = "error"
        jobs[job_id]["error"] = str(e)
    finally:
        # Cleanup temp files
        try:
            if tmp_in_path and os.path.exists(tmp_in_path):
                os.remove(tmp_in_path)
            if tmp_out_path and os.path.exists(tmp_out_path):
                os.remove(tmp_out_path)
        except Exception as e:
            print(f"Cleanup error: {e}")

@app.get("/job_status/{job_id}")
async def job_status(job_id: str):
    if job_id not in jobs:
        raise HTTPException(status_code=404, detail="Job not found")
    return JSONResponse(jobs[job_id])

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server:app", host="0.0.0.0", port=8000, reload=True)
