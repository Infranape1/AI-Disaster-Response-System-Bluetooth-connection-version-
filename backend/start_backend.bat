@echo off
REM Starts the main system so phones on the same Wi-Fi can reach it.
cd /d "%~dp0"
if exist ".venv\Scripts\python.exe" (
    ".venv\Scripts\python.exe" run_server.py
) else (
    python run_server.py
)
pause
