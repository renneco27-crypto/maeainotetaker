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
from phonetic_corrector import corrector

from collections import deque

app = FastAPI(title="Local PC Whisper Server")

# Rolling memory of the last few transcribed segments to provide context to Whisper
recent_context = deque(maxlen=5)

# Job queue for large file uploads
jobs = {}

# Load model on startup
print("Loading faster-whisper model (base)...")
# 'cuda' will use GPU if available, fallback to 'cpu' if not
try:
    model = WhisperModel("base", device="cuda", compute_type="float16")
    print("Loaded on CUDA (GPU)")
except Exception as e:
    print(f"CUDA unavailable, running on CPU: {e}")
    model = WhisperModel("base", device="cpu", compute_type="int8")
    print("Loaded on CPU (int8)")

@app.post("/transcribe")
async def transcribe(request: Request):
    try:
        audio_bytes = await request.body()
        if not audio_bytes:
            raise HTTPException(status_code=400, detail="No audio data provided")
            
        start_time = time.time()
        
        # Build the context prompt from recent history
        context_prompt = "This is a lecture in Tagalog (Filipino), Cebuano (Bisaya), and English. "
        if recent_context:
            context_prompt += " ".join(recent_context)
            
        def run_transcribe():
            audio_io = io.BytesIO(audio_bytes)
            segments, info = model.transcribe(
                audio_io, 
                beam_size=5, 
                initial_prompt=context_prompt
            )
            return " ".join([segment.text for segment in segments]).strip()
        
        raw_text = await asyncio.to_thread(run_transcribe)
        
        # Apply Tagalog/Bisaya phonetic and syllable correction
        corrected_text = corrector.correct_text(raw_text)
        
        # Add the new transcribed text to our rolling memory context
        if corrected_text:
            recent_context.append(corrected_text)
            
        print(f"[LIVE] Transcribed ({time.time() - start_time:.2f}s): {corrected_text}")
        
        return JSONResponse({"text": corrected_text})
        
    except Exception as e:
        print(f"❌ Error during live transcription: {e}")
        return JSONResponse({"error": str(e)}, status_code=500)

@app.post("/transcribe_file")
async def transcribe_file(file: UploadFile = File(...)):
    job_id = str(uuid.uuid4())
    jobs[job_id] = {"status": "processing", "progress": 0, "text": None, "error": None}
    
    # Save uploaded file to memory
    content = await file.read()
    print(f"\n📥 Received audio file upload: {file.filename} ({len(content) / (1024*1024):.2f} MB)")
    
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
                
            print(f"⚡ Applying 1.5x FFmpeg speedup to audio...")
            # Run ffmpeg to speed up 1.5x and convert to 16kHz WAV
            cmd = [
                "ffmpeg", "-y", "-i", tmp_in_path, 
                "-filter:a", "atempo=1.5", 
                "-ar", "16000", "-ac", "1", 
                tmp_out_path
            ]
            subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            
            print(f"🧠 Whisper inference starting...")
            # Transcribe
            segments, info = model.transcribe(
                tmp_out_path, 
                beam_size=5,
                initial_prompt="This is a lecture in Tagalog (Filipino), Cebuano (Bisaya), and English. "
            )
            
            total_duration = getattr(info, "duration", 0.0)
            print(f"⏱️ Total Audio Duration: {total_duration:.1f}s")
            print("--------------------------------------------------")
            
            collected_segments = []
            for segment in segments:
                # Check if client requested cancellation
                if jobs.get(job_id, {}).get("status") == "cancelled":
                    print(f"\n🛑 Job {job_id} was cancelled by user. Stopping Whisper transcription immediately.")
                    return ""

                collected_segments.append(segment.text)
                progress_pct = min(100, int((segment.end / total_duration) * 100)) if total_duration > 0 else 0
                
                # Visual terminal progress bar
                bar_len = 20
                filled_len = int(bar_len * progress_pct / 100)
                bar = "█" * filled_len + "░" * (bar_len - filled_len)
                
                print(f"[{bar}] {progress_pct:3d}% | {segment.start:5.1f}s -> {segment.end:5.1f}s | {segment.text.strip()}")
                
                # Update progress for phone polling
                jobs[job_id]["progress"] = progress_pct
                
            return " ".join(collected_segments).strip()

        # Run the heavy processing in a thread pool
        raw_text = await asyncio.to_thread(run_processing)
        
        if jobs.get(job_id, {}).get("status") == "cancelled":
            print(f"🛑 Job {job_id} cleaned up after cancellation.\n")
            return
            
        # Pass through phonetic correction
        final_text = corrector.correct_text(raw_text)
        
        print("--------------------------------------------------")
        print(f"🎉 Job {job_id} Completed Successfully!\nFinal: {final_text}\n")
        jobs[job_id]["status"] = "completed"
        jobs[job_id]["progress"] = 100
        jobs[job_id]["text"] = final_text
        
    except Exception as e:
        if jobs.get(job_id, {}).get("status") != "cancelled":
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

@app.post("/cancel_job/{job_id}")
async def cancel_job(job_id: str):
    if job_id in jobs:
        jobs[job_id]["status"] = "cancelled"
        print(f"\n🛑 Cancel request received from phone for Job {job_id}")
        return JSONResponse({"message": "Job marked as cancelled", "job_id": job_id})
    return JSONResponse({"error": "Job not found"}, status_code=404)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server:app", host="0.0.0.0", port=8000, reload=True)
