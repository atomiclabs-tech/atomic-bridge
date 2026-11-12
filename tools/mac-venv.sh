#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v uv >/dev/null 2>&1; then
    printf 'Error: uv is required to create the offline Python environment.\n' >&2
    exit 1
fi
if [[ ! -f "$project_root/mac/pyproject.toml" ]]; then
    printf 'Error: mac/pyproject.toml is missing.\n' >&2
    exit 1
fi
venv_path="$project_root/mac/.venv"
if [[ ! -x "$venv_path/bin/python" ]]; then
    uv venv --offline --python 3.12 "$venv_path"
fi
uv pip install --offline --python "$venv_path/bin/python" \
    -r "$project_root/mac/pyproject.toml" --extra dev
