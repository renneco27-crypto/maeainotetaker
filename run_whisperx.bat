@echo off
echo Starting WhisperX Server...
cd pc_server
uvicorn server_whisperx:app --host 0.0.0.0 --port 8000 --reload
pause
