"""Load and compare the operator credential without exposing it in diagnostics."""

import logging
import os
import secrets
import stat
from pathlib import Path

logger = logging.getLogger("atomic_relay.config")


class AdminSecretError(ValueError):
    """An admin credential file cannot be used safely."""


class AdminCredential:
    def __init__(self, secret: str, *, enabled: bool = True):
        self._secret = secret.encode("utf-8")
        self._enabled = enabled

    @classmethod
    def load(cls, path: Path) -> "AdminCredential":
        """Read a regular private file and require at least 32 characters."""
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
            with os.fdopen(descriptor, "rb") as source:
                metadata = os.fstat(source.fileno())
                if not stat.S_ISREG(metadata.st_mode):
                    raise AdminSecretError("component=config operation=admin-secret regular file required")
                if metadata.st_mode & 0o077:
                    raise AdminSecretError("component=config operation=admin-secret private file permissions required")
                # Reject invalid bytes without putting credential bytes in a decode exception.
                text = source.read().decode("utf-8", errors="surrogateescape")
        except OSError as error:
            raise AdminSecretError("component=config operation=admin-secret file unavailable") from error
        if any(0xDC80 <= ord(character) <= 0xDCFF for character in text):
            raise AdminSecretError("component=config operation=admin-secret UTF-8 required")
        secret = text.strip()
        if len(secret) < 32:
            raise AdminSecretError("component=config operation=admin-secret at least 32 characters required")
        if "\n" in secret or "\r" in secret:
            raise AdminSecretError("component=config operation=admin-secret single line required")
        return cls(secret)

    @classmethod
    def ephemeral(cls) -> "AdminCredential":
        logger.warning("component=config operation=admin-secret ephemeral; admin routes unreachable (isolated health/tests only)")
        return cls(secrets.token_urlsafe(32), enabled=False)

    def compare(self, presented: str) -> bool:
        """Compare before session lookup; isolated credentials never authorize anyone."""
        matches = secrets.compare_digest(self._secret, presented.encode("utf-8"))
        return matches and self._enabled

    def authorization(self) -> str:
        """Supply a bearer header to the local operator client."""
        if not self._enabled:
            raise AdminSecretError("component=config operation=admin-secret isolated credential cannot be presented")
        return "Bearer " + self._secret.decode("utf-8")
