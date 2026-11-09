#!/usr/bin/env bash
# clean-dev-logs.sh — Convenient shell wrapper for clean_dev_logs.py
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON_EXEC="python3"

if ! command -v "$PYTHON_EXEC" >/dev/null 2>&1; then
    echo "Error: python3 is required to run the retention script." >&2
    exit 1
fi

exec "$PYTHON_EXEC" "$SCRIPT_DIR/clean_dev_logs.py" "$@"
