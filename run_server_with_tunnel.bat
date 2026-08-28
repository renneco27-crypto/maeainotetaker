@echo off
echo Starting Note Taker PC Server...
start cmd /k "python pc_server\server.py"

echo.
echo Starting Permanent Internet Tunnel (localtunnel)...
echo Your server will be globally available at: https://cortes-notetaker.loca.lt
start cmd /k "npx localtunnel --port 8000 --subdomain cortes-notetaker"

echo.
echo Both services are starting in separate windows!
echo Make sure neither window is closed.
pause
