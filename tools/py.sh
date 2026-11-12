#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python_path="$project_root/mac/.venv/bin/python"
if [[ ! -x "$python_path" ]]; then
    printf 'Error: mac/.venv is missing; run tools/mac-venv.sh first.\n' >&2
    exit 1
fi
cd "$project_root/mac"
exec "$python_path" "$@"
