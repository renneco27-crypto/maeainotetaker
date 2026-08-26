import requests
import time
import io
import soundfile as sf
import numpy as np
import urllib3
urllib3.disable_warnings()

print("Generating 10 seconds of dummy audio...")
# Generate 10 seconds of 16kHz audio (sine wave)
sample_rate = 16000
duration = 10
t = np.linspace(0, duration, int(sample_rate * duration), False)
audio_data = 0.5 * np.sin(2 * np.pi * 440 * t)  # 440Hz A4 tone

# Save to BytesIO
dummy_audio = io.BytesIO()
sf.write(dummy_audio, audio_data, sample_rate, format='WAV')
dummy_audio.seek(0)

# Upload to server
print("Uploading to POST /transcribe_file...")
try:
    response = requests.post(
        "http://127.0.0.1:8000/transcribe_file",
        files={"file": ("dummy.wav", dummy_audio, "audio/wav")}
    )
    print(f"Status Code: {response.status_code}")
    print(f"Response: {response.json()}")
    
    if response.status_code == 200:
        job_id = response.json().get("job_id")
        
        print(f"\nPolling job status for {job_id}...")
        while True:
            time.sleep(2)
            status_res = requests.get(f"http://127.0.0.1:8000/job_status/{job_id}")
            if status_res.status_code == 200:
                data = status_res.json()
                print(f"Status: {data['status']}")
                if data['status'] in ['completed', 'error']:
                    print(f"Final Result: {data}")
                    break
            else:
                print(f"Poll Error: {status_res.status_code}")
                break
                
except Exception as e:
    print(f"Connection failed (is the server running?): {e}")
