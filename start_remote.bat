@echo off
title Haier Smart TV Remote Web App
echo ===================================================
echo           Haier Smart TV PC Remote
echo ===================================================
echo TV IP: 192.168.0.107
echo Starting server on http://localhost:5000 ...
echo.

cd /d "%~dp0"

start "" http://localhost:5000
python server.py

pause
