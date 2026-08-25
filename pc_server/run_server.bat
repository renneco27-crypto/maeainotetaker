@echo off
echo Installing requirements...
pip install -r requirements.txt

echo Starting Local PC Whisper Server...
python server.py
pause
