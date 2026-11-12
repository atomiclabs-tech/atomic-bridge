"""Create once and read a private operator credential without exposing its contents."""

import os
import secrets
import stat
import tempfile
from pathlib import Path


def read_secret(path: Path) -> str:
    """Require a private regular UTF-8 file with a single-line 32-character secret."""
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as source:
            metadata = os.fstat(source.fileno())
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077:
                raise ValueError("operator admin secret must be a private regular file")
            text = source.read().decode("utf-8", errors="surrogateescape")
    except OSError as error:
        raise ValueError("operator admin secret is unavailable") from error
    value = text.strip()
    if (len(value) < 32 or any(character in value for character in "\r\n\0")
            or any(0xDC80 <= ord(character) <= 0xDCFF for character in value)):
        raise ValueError("operator admin secret must be valid UTF-8, single-line and at least 32 characters")
    return value


def create_if_missing(path: Path, *, generate=lambda: secrets.token_urlsafe(32)) -> bool:
    """Install one complete 0600 file without overwriting an existing credential."""
    if path.exists() or path.is_symlink():
        read_secret(path)
        return False
    value = generate()
    if not isinstance(value, str) or len(value) < 32 or any(character in value for character in "\r\n\0"):
        raise ValueError("generated operator admin secret is invalid")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".admin-", delete=False) as output:
            temporary = Path(output.name)
            os.fchmod(output.fileno(), 0o600)
            data = (value + "\n").encode("utf-8")
            if output.write(data) != len(data):
                raise OSError("operator credential write was incomplete")
            output.flush()
            os.fsync(output.fileno())
        try:
            os.link(temporary, path)
        except FileExistsError:
            read_secret(path)
            return False
        return True
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
