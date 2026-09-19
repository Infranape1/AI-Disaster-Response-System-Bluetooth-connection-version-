#!/usr/bin/env bash
# Starts the main system so phones on the same Wi-Fi can reach it.
cd "$(dirname "$0")"
if [ -x ".venv/bin/python" ]; then
    ./.venv/bin/python run_server.py
else
    python3 run_server.py
fi
