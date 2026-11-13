"""Read a private PIN file without exposing its contents in diagnostics."""

import os
import re
import stat
from pathlib import Path

_MAX_FILE_BYTES = 1024


class UnlockPinError(ValueError):
    """The configured PIN file cannot be used safely."""


class UnlockPin:
    @staticmethod
    def load(path: Path) -> str:
        """Read at most 1 KiB from a private, current-user, non-symlink PIN file."""
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
            with os.fdopen(descriptor, "rb", buffering=0) as source:
                metadata = os.fstat(source.fileno())
                if not stat.S_ISREG(metadata.st_mode):
                    raise UnlockPinError("regular file required")
                if metadata.st_uid != os.geteuid():
                    raise UnlockPinError("current user ownership required")
                if metadata.st_mode & 0o077:
                    raise UnlockPinError("private file permissions required")
                if metadata.st_size > _MAX_FILE_BYTES:
                    raise UnlockPinError("file too large")
                content = source.read(_MAX_FILE_BYTES + 1)
                if len(content) > _MAX_FILE_BYTES:
                    raise UnlockPinError("file too large")
                text = content.decode("utf-8", errors="surrogateescape")
        except OSError as error:
            raise UnlockPinError("file unavailable") from error
        if any(0xDC80 <= ord(character) <= 0xDCFF for character in text):
            raise UnlockPinError("UTF-8 required")
        pin = text.strip()
        if re.fullmatch(r"[0-9]{4,16}", pin) is None:
            raise UnlockPinError("digits required")
        return pin
