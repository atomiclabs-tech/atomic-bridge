# Ported from plugin/relay/media.py at upstream commit da782a8.
"""Opaque media tokens with TTL, LRU and identity-checked owned cleanup."""

import asyncio
import logging
import secrets
import stat
import time
from collections import OrderedDict
from dataclasses import dataclass, field

from atomic_relay.media.files import identity, inspect_file, open_verified
from atomic_relay.media.paths import MediaError

logger = logging.getLogger("atomic_relay.media")


@dataclass
class MediaEntry:
    token: str = field(repr=False)
    path: object = field(repr=False)
    content_type: str
    file_name: str | None = field(repr=False)
    size: int
    sha256: str
    identity: tuple
    expires_at: float
    sensitive: bool = False


class MediaRegistry:
    def __init__(self, paths, ttl_seconds, *, max_entries=500, clock=time.time):
        self.paths, self.ttl_seconds, self.max_entries, self.clock = paths, ttl_seconds, max_entries, clock
        self.entries, self.owned = OrderedDict(), {}

    def register(self, path, content_type, file_name=None, sensitive=False, owned_file=False, *, upload=False):
        if not isinstance(content_type, str) or not content_type.strip() or any(char in content_type for char in "\r\n"):
            raise MediaError("media content type is invalid")
        if file_name is not None and not isinstance(file_name, str):
            raise MediaError("media file label is invalid")
        if not isinstance(sensitive, bool) or not isinstance(owned_file, bool):
            raise MediaError("media flags must be booleans")
        self.cleanup()
        path = self.paths.validate(path, upload=upload)
        if owned_file and not self.paths.owned(path):
            raise MediaError("owned media requires a managed upload path", 403)
        size, digest, file_id = inspect_file(path, self.paths.max_bytes)
        entry = MediaEntry(secrets.token_urlsafe(16), path, content_type, file_name, size, digest, file_id,
                           self.clock() + self.ttl_seconds, sensitive)
        self.entries[entry.token] = entry
        if owned_file:
            self.owned[path] = file_id
        while len(self.entries) > self.max_entries:
            self.entries.popitem(last=False)
        self._retire()
        return entry

    def get(self, token):
        self.cleanup()
        entry = self.entries.get(token)
        if entry is not None:
            self.entries.move_to_end(token)
        return entry

    def mark_sensitive(self, token):
        entry = self.get(token)
        if entry is None:
            return False
        entry.sensitive = True
        return True

    def open(self, entry):
        return open_verified(entry.path, entry.size, entry.identity, entry.sha256)

    def cleanup(self):
        expired = [token for token, entry in self.entries.items() if self.clock() > entry.expires_at]
        for token in expired:
            del self.entries[token]
        self._retire()
        return len(expired)

    def _retire(self):
        active = {entry.path for entry in self.entries.values()}
        for path, expected in tuple(self.owned.items()):
            if path in active:
                continue
            try:
                info = path.lstat()
            except FileNotFoundError:
                del self.owned[path]
                continue
            if identity(info) != expected or not stat.S_ISREG(info.st_mode):
                logger.warning("component=media operation=cleanup ownership changed; retaining file")
                del self.owned[path]
                continue
            path.unlink()
            del self.owned[path]

    async def maintain(self):
        while True:
            await asyncio.sleep(300)
            try:
                self.cleanup()
            except OSError as error:
                logger.exception("component=media operation=cleanup failure_type=%s", type(error).__name__,
                                 exc_info=(RuntimeError, RuntimeError("media cleanup failed"), error.__traceback__))

    def close(self):
        self.entries.clear()
        self._retire()
