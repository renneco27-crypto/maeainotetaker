import io
import time
import uuid
import asyncio
import tempfile
import os
from fastapi import FastAPI, Request, HTTPException, UploadFile, File
from fastapi.responses import JSONResponse
import whisperx
import torch

app = FastAPI(title="Local PC WhisperX Server")
jobs = {}

device = "cpu"
compute_type = "int8"  # Use INT8 for CPU speed

print("==================================================")
print("Loading WhisperX model (base)... this may take a moment.")
print("==================================================")
# Load the model with CPU and INT8 optimization
model = whisperx.load_model("base", device, compute_type=compute_type)
print("✅ WhisperX loaded successfully!")

@app.post("/transcribe")
async def transcribe(request: Request):
    try:
        audio_bytes = await request.body()
        if len(audio_bytes) < 200:
            return JSONResponse({"text": ""})
            
        with tempfile.NamedTemporaryFile(delete=False, suffix=".wav") as tmp_in:
            tmp_in.write(audio_bytes)
            tmp_in_path = tmp_in.name
            
        audio = whisperx.load_audio(tmp_in_path)
        # WhisperX automatically uses VAD and chunks the audio
        result = model.transcribe(audio, batch_size=1)
        
        text = " ".join([seg["text"] for seg in result.get("segments", [])])
        os.remove(tmp_in_path)
        return JSONResponse({"text": text.strip()})
    except Exception as e:
        print(f"Live Transcribe Error: {e}")
        return JSONResponse({"text": ""})

@app.post("/transcribe_file")
async def transcribe_file(file: UploadFile = File(...)):
    job_id = str(uuid.uuid4())
    jobs[job_id] = {"status": "processing", "progress": 0, "text": None, "segments": []}
    
    content = await file.read()
    print(f"\n📥 Received WhisperX audio file upload: {file.filename}")
    
    asyncio.create_task(process_job(job_id, content))
    return JSONResponse({"job_id": job_id})

async def process_job(job_id: str, content: bytes):
    tmp_in_path = None
    try:
        def run_processing():
            with tempfile.NamedTemporaryFile(delete=False, suffix=".wav") as tmp_in:
                tmp_in.write(content)
                tmp_in_path = tmp_in.name
                
            print(f"🧠 WhisperX loading audio...")
            audio = whisperx.load_audio(tmp_in_path)
            
            print(f"🧠 WhisperX processing started! (Note: Progress will jump to 100% when finished)")
            # WhisperX natively handles VAD, parallel chunking, and batching
            result = model.transcribe(audio, batch_size=4)
            
            collected_segments = []
            for seg in result.get("segments", []):
                start_ms = int(seg["start"] * 1000)
                end_ms = int(seg["end"] * 1000)
                text_clean = seg["text"].strip()
                
                collected_segments.append({
                    "start_ms": start_ms,
                    "end_ms": end_ms,
                    "text": text_clean
                })
                print(f"[{start_ms/1000:5.1f}s -> {end_ms/1000:5.1f}s] {text_clean}")
                
            os.remove(tmp_in_path)
            return collected_segments
            
        segments = await asyncio.to_thread(run_processing)
        
        if jobs.get(job_id, {}).get("status") == "cancelled":
            print(f"🛑 Job {job_id} cancelled.")
            return

        jobs[job_id]["status"] = "completed"
        jobs[job_id]["progress"] = 100
        jobs[job_id]["text"] = " ".join([s["text"] for s in segments])
        jobs[job_id]["segments"] = segments
        print(f"🎉 Job {job_id} Completed via WhisperX with {len(segments)} segments!")
        
    except Exception as e:
        if jobs.get(job_id, {}).get("status") != "cancelled":
            jobs[job_id]["status"] = "error"
            print(f"❌ Job {job_id} Error: {e}")

@app.get("/job_status/{job_id}")
async def get_job_status(job_id: str):
    if job_id not in jobs:
        raise HTTPException(status_code=404, detail="Job not found")
    return JSONResponse(jobs[job_id])
    
@app.delete("/cancel_job/{job_id}")
async def cancel_job(job_id: str):
    if job_id in jobs:
        jobs[job_id]["status"] = "cancelled"
    return JSONResponse({"status": "cancelled"})
