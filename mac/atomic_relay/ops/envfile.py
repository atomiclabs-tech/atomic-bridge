"""Edit selected operator environment assignments without changing other lines."""

import os
import re
import stat
import tempfile
from pathlib import Path


def atomic_write(path: Path, data: bytes):
    """Replace one private regular file atomically in its existing directory."""
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise ValueError("operator file must be regular and must not be a symlink")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".atomic-", delete=False) as output:
            temporary = Path(output.name)
            os.fchmod(output.fileno(), 0o600)
            if output.write(data) != len(data):
                raise OSError("operator file write was incomplete")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _value(value):
    if not isinstance(value, str) or any(character in value for character in "\r\n\0'"):
        raise ValueError("operator environment value has unsupported quoting or control characters")
    return value if re.fullmatch(r"[A-Za-z0-9_./:~-]+", value) else "'" + value + "'"


def update(path: Path, values: dict[str, str]) -> bool:
    """Set every named key, preserving all untouched bytes; return whether replaced."""
    if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode):
        raise ValueError("operator environment file must be a regular non-symlink file")
    encoded = {}
    for key, value in values.items():
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise ValueError("operator environment key is invalid")
        encoded[key] = _value(value)
    original = path.read_bytes()
    lines, found = [], set()
    for line in original.decode("utf-8").splitlines(keepends=True):
        key = line.partition("=")[0].strip()
        if key in encoded:
            ending = "\r\n" if line.endswith("\r\n") else "\n" if line.endswith("\n") else ""
            line = key + "=" + encoded[key] + ending
            found.add(key)
        lines.append(line)
    for key in (key for key in encoded if key not in found):
        if lines and not lines[-1].endswith("\n"):
            lines[-1] += "\n"
        lines.append(key + "=" + encoded[key] + "\n")
    data = "".join(lines).encode("utf-8")
    if data == original and stat.S_IMODE(path.stat().st_mode) == 0o600:
        return False
    atomic_write(path, data)
    return True
