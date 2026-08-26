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

# Load model on startup with maximum C++ CPU threads and parallel workers
print(f"Loading faster-whisper C++ model (base) across {os.cpu_count()} CPU threads...")
try:
    model = WhisperModel("base", device="cuda", compute_type="float16", cpu_threads=os.cpu_count() or 4)
    print("Loaded on CUDA (GPU)")
except Exception as e:
    print(f"CUDA unavailable, running optimized on C++ CPU engine: {e}")
    model = WhisperModel(
        "base", 
        device="cpu", 
        compute_type="int8", 
        cpu_threads=os.cpu_count() or 4,
        num_workers=2
    )
    print(f"Loaded on C++ CPU (int8, {os.cpu_count()} threads, 2 workers)")

import numpy as np

# Vocabulary hint: English first, then Tagalog/Bisaya for mixed-language Philippine lectures
VOCAB_PROMPT = "English, Tagalog, Bisaya, Cebuano, Filipino."

@app.post("/transcribe")
async def transcribe(request: Request):
    try:
        audio_bytes = await request.body()
        if not audio_bytes:
            raise HTTPException(status_code=400, detail="No audio data provided")
            
        start_time = time.time()
        
        # 1. Fast RMS Energy Gate: Instantly skip pure silence / background microphone hiss (<1ms check)
        if len(audio_bytes) > 44:
            # Skip 44-byte WAV header and inspect raw 16-bit PCM samples
            pcm_samples = np.frombuffer(audio_bytes[44:], dtype=np.int16)
            if len(pcm_samples) > 0:
                rms = np.sqrt(np.mean(pcm_samples.astype(np.float32) ** 2))
                if rms < 200:  # Silence / very quiet background hiss
                    return JSONResponse({"text": ""})
            
        def run_transcribe():
            audio_io = io.BytesIO(audio_bytes)
            segments, info = model.transcribe(
                audio_io, 
                task="transcribe",
                beam_size=5,
                best_of=5,
                temperature=0.0,
                vad_filter=True,
                vad_parameters=dict(min_silence_duration_ms=1000, speech_pad_ms=150),
                condition_on_previous_text=True, # Allow context to form full sentences
                repetition_penalty=1.2, # Penalizes token repetition loops
                compression_ratio_threshold=2.4, # Drops repetitive hallucinations
                no_speech_threshold=0.6,
                initial_prompt=VOCAB_PROMPT
            )
            
            valid_texts = []
            for segment in segments:
                if segment.compression_ratio > 2.4:
                    continue
                    
                # Just apply basic phonetic dictionary, do NOT suppress or drop words
                text_clean = corrector.correct_text(segment.text.strip())

                if text_clean:
                    valid_texts.append(text_clean)
                
            return " ".join(valid_texts).strip()
        
        corrected_text = await asyncio.to_thread(run_transcribe)
        
        if not corrected_text:
            return JSONResponse({"text": ""})
        
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
                
            print(f"⚡ Converting audio to 16kHz mono WAV (1.0x native speed)...")
            # Run ffmpeg to convert to 16kHz WAV at native 1.0x speed
            cmd = [
                "ffmpeg", "-y", "-i", tmp_in_path, 
                # "-filter:a", "atempo=1.5",  # Disabled 1.5x speedup per user request
                "-ar", "16000", "-ac", "1", 
                tmp_out_path
            ]
            subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            
            print(f"🧠 Whisper C++ inference starting...")
            # word_timestamps=True gives per-word probability so we can drop only vague words, not whole sentences
            segments, info = model.transcribe(
                tmp_out_path, 
                task="transcribe",
                beam_size=5,  # Maximum accuracy (slower but checks multiple paths)
                best_of=5,
                temperature=0.0,
                vad_filter=True,  # Re-enabled VAD to stop the massive hallucination loops
                vad_parameters=dict(min_silence_duration_ms=2000, speech_pad_ms=200),
                condition_on_previous_text=True,
                repetition_penalty=1.2,
                compression_ratio_threshold=2.4,
                no_speech_threshold=0.6,
                initial_prompt=VOCAB_PROMPT
            )
            
            total_duration = getattr(info, "duration", 0.0)
            print(f"⏱️ Total Audio Duration: {total_duration:.1f}s")
            print("--------------------------------------------------")
            
            collected_segments = []
            # Keep track of recent sentences to block 3x hallucinations
            recent_sentences = []

            for segment in segments:
                # Check if client requested cancellation
                if jobs.get(job_id, {}).get("status") == "cancelled":
                    print(f"\n🛑 Job {job_id} was cancelled by user. Stopping Whisper transcription immediately.")
                    return []

                orig_start_s = segment.start
                orig_end_s = segment.end

                # Skip hallucinated silence or repetition loops at segment level
                if segment.compression_ratio > 2.4:
                    print(f"  [SKIPPED {orig_start_s:.1f}s] high compression_ratio: {segment.compression_ratio:.2f} (text: {segment.text.strip()})")
                    continue

                # Just apply basic phonetic dictionary, do NOT suppress or drop words
                text_clean = corrector.correct_text(segment.text.strip())

                if not text_clean:
                    continue

                # Just require 1 valid word, don't drop short sentences!
                real_words = [w for w in text_clean.split() if len(w) > 1]
                if len(real_words) < 1:
                    print(f"  [SKIPPED {orig_start_s:.1f}s] no real words found")
                    continue

                # Check if this exact sentence has repeated 3 times in a row (Hallucination blocker)
                recent_sentences.append(text_clean)
                if len(recent_sentences) > 3:
                    recent_sentences.pop(0)
                
                if len(recent_sentences) == 3 and recent_sentences[0] == recent_sentences[1] == recent_sentences[2]:
                    print(f"  [SKIPPED {orig_start_s:.1f}s] blocked 3x repetition loop: {text_clean}")
                    # Pop the last one so we don't permanently block the text if they actually said it again later,
                    # but it breaks the immediate hallucination loop.
                    recent_sentences.pop()
                    continue

                # Exact 1:1 original audio timestamps
                orig_start_ms = int(segment.start * 1000)
                orig_end_ms = int(segment.end * 1000)

                collected_segments.append({
                    "start_ms": orig_start_ms,
                    "end_ms": orig_end_ms,
                    "text": text_clean
                })
                
                progress_pct = min(100, int((segment.end / total_duration) * 100)) if total_duration > 0 else 0
                
                # Visual terminal progress bar
                bar_len = 20
                filled_len = int(bar_len * progress_pct / 100)
                bar = "█" * filled_len + "░" * (bar_len - filled_len)
                
                print(f"[{bar}] {progress_pct:3d}% | {orig_start_ms/1000:5.1f}s -> {orig_end_ms/1000:5.1f}s | {text_clean}")
                
                # Update progress for phone polling
                jobs[job_id]["progress"] = progress_pct
                
            return collected_segments

        # Run the heavy processing in a thread pool
        raw_segments = await asyncio.to_thread(run_processing)
        
        if jobs.get(job_id, {}).get("status") == "cancelled":
            print(f"🛑 Job {job_id} cleaned up after cancellation.\n")
            return
            
        # Processing is already complete, just format for output
        processed_segments = raw_segments
        final_text = " ".join([s["text"] for s in processed_segments])
        
        print("--------------------------------------------------")
        print(f"🎉 Job {job_id} Completed Successfully with {len(processed_segments)} timestamped segments!\n")
        jobs[job_id]["status"] = "completed"
        jobs[job_id]["progress"] = 100
        jobs[job_id]["text"] = final_text
        jobs[job_id]["segments"] = processed_segments
        
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
