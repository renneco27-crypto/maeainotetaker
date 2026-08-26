import os
import sys
import tempfile
import subprocess
import concurrent.futures
import whisperx

def main():
    if len(sys.argv) < 2:
        print("Usage: python transcribe_local.py <path_to_audio_file>")
        sys.exit(1)

    input_file = sys.argv[1]
    if not os.path.exists(input_file):
        print(f"Error: File '{input_file}' not found.")
        sys.exit(1)

    print("==================================================")
    print("Loading WhisperX model (base) on CPU...")
    print("==================================================")
    
    # Load WhisperX with CPU and INT8 optimization
    model = whisperx.load_model("base", "cpu", compute_type="int8")
    print("✅ WhisperX loaded successfully!")

    print(f"\n📥 Preparing local file: {input_file}")
    
    # Run ffmpeg to split into 3-minute chunks
    chunk_dir = tempfile.mkdtemp()
    chunk_pattern = os.path.join(chunk_dir, "chunk_%03d.wav")
    print("⚡ Slicing audio into 3-minute chunks via FFmpeg...")
    cmd = [
        "ffmpeg", "-y", "-i", input_file, 
        "-f", "segment", "-segment_time", "180",
        "-ar", "16000", "-ac", "1", 
        chunk_pattern
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    
    # Get total duration
    cmd_probe = ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", input_file]
    probe_out = subprocess.run(cmd_probe, stdout=subprocess.PIPE, text=True).stdout
    total_duration = float(probe_out.strip()) if probe_out.strip() else 0.0
    print(f"⏱️ Total Audio Duration: {total_duration:.1f}s")
    print("--------------------------------------------------")
    
    chunk_files = sorted([f for f in os.listdir(chunk_dir) if f.endswith(".wav")])
    total_chunks = len(chunk_files)
    
    collected_segments = []
    
    def process_chunk(chunk_idx, chunk_file):
        offset_s = chunk_idx * 180.0
        chunk_path = os.path.join(chunk_dir, chunk_file)
            
        audio_chunk = whisperx.load_audio(chunk_path)
        # WhisperX automatically uses VAD
        result = model.transcribe(audio_chunk, batch_size=1)
        
        chunk_results = []
        for seg in result.get("segments", []):
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
            chunk_segments = future.result()
            collected_segments.extend(chunk_segments)
            finished_chunks += 1
            progress_pct = min(100, int((finished_chunks / total_chunks) * 100))
            print(f"✅ Chunk finished! Total Progress: {progress_pct}%")
    
    collected_segments.sort(key=lambda x: x["start_ms"])
    
    final_text = "\n".join([f"[{s['start_ms']/1000:.1f}s] {s['text']}" for s in collected_segments])
    
    out_file = input_file + "_transcript.txt"
    with open(out_file, "w", encoding="utf-8") as f:
        f.write(final_text)
        
    print("--------------------------------------------------")
    print(f"🎉 Transcription Complete! Saved to: {out_file}")

if __name__ == "__main__":
    main()
