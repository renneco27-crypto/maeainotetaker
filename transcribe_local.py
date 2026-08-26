import os
import sys
import tempfile
import subprocess
import concurrent.futures

# Make sure we can import from pc_server
sys.path.append(os.path.join(os.path.dirname(__file__), "pc_server"))
from faster_whisper import WhisperModel
from phonetic_corrector import corrector

# Vocabulary hint
VOCAB_PROMPT = "English, Tagalog, Bisaya, Cebuano, Filipino."

def main():
    if len(sys.argv) < 2:
        print("Usage: python transcribe_local.py <path_to_audio_file>")
        sys.exit(1)

    input_file = sys.argv[1]
    if not os.path.exists(input_file):
        print(f"Error: File '{input_file}' not found.")
        sys.exit(1)

    print(f"Loading faster-whisper C++ model (small) across {os.cpu_count()} CPU threads...")
    try:
        model = WhisperModel("small", device="cuda", compute_type="float16", cpu_threads=os.cpu_count() or 4)
        print("Loaded on CUDA (GPU)")
    except Exception as e:
        print(f"CUDA unavailable, running optimized on C++ CPU engine")
        model = WhisperModel(
            "small", 
            device="cpu", 
            compute_type="int8", 
            cpu_threads=os.cpu_count() or 4,
            num_workers=2
        )

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
            
        segments_gen, _ = model.transcribe(
            chunk_path, 
            task="transcribe",
            beam_size=5,
            best_of=5,
            temperature=0.0,
            vad_filter=True,
            vad_parameters=dict(min_silence_duration_ms=2000, speech_pad_ms=200),
            condition_on_previous_text=True,
            repetition_penalty=1.2,
            compression_ratio_threshold=2.4,
            no_speech_threshold=0.6,
            initial_prompt=VOCAB_PROMPT
        )
        
        chunk_results = []
        recent_sentences = []
        
        for segment in segments_gen:
            orig_start_s = segment.start + offset_s
            orig_end_s = segment.end + offset_s
            
            if segment.compression_ratio > 2.4:
                continue
                
            text_clean = corrector.correct_text(segment.text.strip())
            if not text_clean:
                continue
                
            real_words = [w for w in text_clean.split() if len(w) > 1]
            if len(real_words) < 1:
                continue
                
            recent_sentences.append(text_clean)
            if len(recent_sentences) > 3:
                recent_sentences.pop(0)
                
            if len(recent_sentences) == 3 and recent_sentences[0] == recent_sentences[1] == recent_sentences[2]:
                recent_sentences.pop()
                continue
                
            chunk_results.append({
                "start_ms": int(orig_start_s * 1000),
                "end_ms": int(orig_end_s * 1000),
                "text": text_clean
            })
            
            print(f"[Thread-{chunk_idx}] {orig_start_s:5.1f}s -> {orig_end_s:5.1f}s | {text_clean}")
        
        return chunk_results

    print(f"🧠 Processing {total_chunks} chunks in parallel...")
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
