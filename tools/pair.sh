#!/usr/bin/env bash
set -euo pipefail
# Uses tools/py.sh's offline environment; accepts the pair command's arguments.
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -x "$project_root/tools/py.sh" ]]; then
    printf 'Error: tools/py.sh is missing or not executable.\n' >&2
    exit 1
fi
exec "$project_root/tools/py.sh" -m atomic_relay pair "$@"
