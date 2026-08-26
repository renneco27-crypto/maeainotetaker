import io
import time
import uuid
import asyncio
import tempfile
import os
import subprocess
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
        
        final_text = text.strip()
        if final_text:
            print(f"[LIVE] Transcribed: {final_text}")
            
        return JSONResponse({"text": final_text})
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
                
            print(f"🧠 Slicing audio into 3-minute chunks via FFmpeg for WhisperX...")
            chunk_dir = tempfile.mkdtemp()
            chunk_pattern = os.path.join(chunk_dir, "chunk_%03d.wav")
            cmd = [
                "ffmpeg", "-y", "-i", tmp_in_path, 
                "-f", "segment", "-segment_time", "180",
                "-ar", "16000", "-ac", "1", 
                chunk_pattern
            ]
            subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            
            chunk_files = sorted([f for f in os.listdir(chunk_dir) if f.endswith(".wav")])
            total_chunks = len(chunk_files)
            
            collected_segments = []
            import concurrent.futures
            
            def process_chunk(chunk_idx, chunk_file):
                offset_s = chunk_idx * 180.0
                chunk_path = os.path.join(chunk_dir, chunk_file)
                
                if jobs.get(job_id, {}).get("status") == "cancelled":
                    return []
                    
                audio_chunk = whisperx.load_audio(chunk_path)
                result = model.transcribe(audio_chunk, batch_size=1)
                
                chunk_results = []
                for seg in result.get("segments", []):
                    if jobs.get(job_id, {}).get("status") == "cancelled":
                        break
                        
                    orig_start_s = seg["start"] + offset_s
                    orig_end_s = seg["end"] + offset_s
                    text_clean = seg["text"].strip()
                    
                    if not text_clean:
                        continue
                        
                    chunk_results.append({
                        "start_ms": int(orig_start_s * 1000),
                        "end_ms": int(orig_end_s * 1000),
                        "text": text_clean
                    })
                    print(f"[Thread-{chunk_idx}] {orig_start_s:5.1f}s -> {orig_end_s:5.1f}s | {text_clean}")
                
                return chunk_results

            print(f"🧠 Processing {total_chunks} WhisperX chunks in parallel...")
            finished_chunks = 0
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
                futures = {executor.submit(process_chunk, idx, f): idx for idx, f in enumerate(chunk_files)}
                
                for future in concurrent.futures.as_completed(futures):
                    if jobs.get(job_id, {}).get("status") == "cancelled":
                        print(f"\n🛑 Job {job_id} was cancelled by user. Stopping immediately.")
                        executor.shutdown(wait=False, cancel_futures=True)
                        return []
                        
                    chunk_segments = future.result()
                    collected_segments.extend(chunk_segments)
                    
                    finished_chunks += 1
                    progress_pct = min(100, int((finished_chunks / total_chunks) * 100))
                    jobs[job_id]["progress"] = progress_pct
                    print(f"✅ Chunk finished! Total Progress: {progress_pct}%")
            
            collected_segments.sort(key=lambda x: x["start_ms"])
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
