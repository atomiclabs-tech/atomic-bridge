# Ported from plugin/relay/media.py and plugin/relay/server.py at upstream commit da782a8.
"""Bounded file hashing, upload writes and image signature checks."""

import hashlib
import os
import stat
import tempfile
from contextlib import ExitStack
from pathlib import Path

from atomic_relay.media.paths import MediaError

CHUNK_SIZE = 64 * 1024


def identity(info):
    return info.st_dev, info.st_ino


def open_nofollow(path):
    """Open a regular file without following any path-component symlink."""
    path = Path(path)
    flags = os.O_RDONLY | os.O_NOFOLLOW
    try:
        with ExitStack() as directories:
            parent = os.open(path.anchor, flags | os.O_DIRECTORY)
            directories.callback(os.close, parent)
            for name in path.parts[1:-1]:
                parent = os.open(name, flags | os.O_DIRECTORY, dir_fd=parent)
                directories.callback(os.close, parent)
            descriptor = os.open(path.name, flags | os.O_NONBLOCK, dir_fd=parent)
            try:
                if not stat.S_ISREG(os.fstat(descriptor).st_mode):
                    raise MediaError("registered media changed", 409)
                return os.fdopen(descriptor, "rb")
            except (OSError, MediaError):
                os.close(descriptor)
                raise
    except OSError as error:
        raise MediaError("registered media changed", 409) from error


def inspect_file(path, max_bytes):
    digest, size = hashlib.sha256(), 0
    with open_nofollow(path) as source:
        info = os.fstat(source.fileno())
        while chunk := source.read(CHUNK_SIZE):
            size += len(chunk)
            if size > max_bytes:
                raise MediaError("media file exceeds the size limit", 413)
            digest.update(chunk)
    return size, digest.hexdigest(), identity(info)


def open_verified(path, expected_size, expected_identity, expected_digest):
    source = open_nofollow(path)
    try:
        info = os.fstat(source.fileno())
        if identity(info) != expected_identity or info.st_size != expected_size:
            raise MediaError("registered media changed", 409)
        digest, size = hashlib.sha256(), 0
        while chunk := source.read(CHUNK_SIZE):
            size += len(chunk)
            if size > expected_size:
                raise MediaError("registered media changed", 409)
            digest.update(chunk)
        if digest.hexdigest() != expected_digest:
            raise MediaError("registered media integrity changed", 409)
        source.seek(0)
        return source
    except (OSError, MediaError):
        source.close()
        raise


def image_type(source, declared):
    if not declared.lower().startswith("image/"):
        return declared
    head = source.read(16)
    source.seek(0)
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if head.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if head.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "image/webp"
    raise MediaError("media image signature is invalid", 415)


class UploadFile:
    def __init__(self, directory, max_bytes):
        descriptor, name = tempfile.mkstemp(prefix="atomic-upload-", dir=directory)
        self.path, self.output = Path(name), os.fdopen(descriptor, "wb")
        self.max_bytes, self.size, self.digest, self.committed = max_bytes, 0, hashlib.sha256(), False

    def __enter__(self):
        return self

    def write(self, chunk):
        if len(chunk) > CHUNK_SIZE:
            raise MediaError("upload chunk exceeds the streaming budget")
        if self.size + len(chunk) > self.max_bytes:
            raise MediaError("upload exceeds the size limit", 413)
        if self.output.write(chunk) != len(chunk):
            raise OSError("incomplete upload write")
        self.size += len(chunk)
        self.digest.update(chunk)

    def finish(self):
        self.output.close()

    def __exit__(self, kind, value, traceback):
        self.output.close()
        if not self.committed:
            self.path.unlink(missing_ok=True)
