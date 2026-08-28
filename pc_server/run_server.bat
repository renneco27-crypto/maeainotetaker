@echo off
cd ..
echo ==============================================
echo   NOTE TAKER - UNIFIED SERVER (Wi-Fi + Cloud)
echo ==============================================
echo.
echo Starting PC Server for Local Wi-Fi connections...
start /B python pc_server\server.py

echo.
echo Starting Permanent Internet Tunnel (with Auto-Reconnect)...
echo This ensures the phone can always reach the PC via:
echo https://cortes-notetaker.loca.lt
echo.

:tunnel_loop
npx localtunnel --local-host 127.0.0.1 --port 8000 --subdomain cortes-notetaker
echo.
echo [WARN] Tunnel disconnected or network reset. Auto-reconnecting in 3 seconds...
timeout /t 3 /nobreak >nul
goto tunnel_loop
