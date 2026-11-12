#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -f "$project_root/tools/install_mac.py" || ! -x "$project_root/tools/py.sh" ]]; then
    printf 'Error: installer implementation and tools/py.sh must be present.\n' >&2
    exit 1
fi
exec "$project_root/tools/py.sh" "$project_root/tools/install_mac.py" "$@"
