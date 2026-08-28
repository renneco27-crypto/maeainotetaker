@echo off
echo ==============================================
echo   NOTE TAKER - UNIFIED SERVER (Wi-Fi + Cloud)
echo ==============================================
echo.
echo Starting PC Server for Local Wi-Fi connections...
start /B python pc_server\server.py

echo.
echo Starting Permanent Internet Tunnel...
echo This ensures the phone can always reach the PC via:
echo https://cortes-notetaker.loca.lt
echo.
npx localtunnel --port 8000 --subdomain cortes-notetaker
pause
