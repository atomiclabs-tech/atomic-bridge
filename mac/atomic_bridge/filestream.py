"""Bounded local-file hashing and verified atomic file downloads."""

import hashlib
import logging
import mimetypes
import os
import re
import stat
import tempfile
from dataclasses import dataclass
from pathlib import Path

from .tool_support import ToolFailure

logger = logging.getLogger("atomic_bridge")
MAX_FILE_BYTES = 256 * 1024 * 1024
_MARKER = re.compile(r"MEDIA:hermes-relay://([A-Za-z0-9_-]{16,128})\Z")
_SHA256 = re.compile(r"[A-Fa-f0-9]{64}\Z")


class FileTransferError(ToolFailure):
    def __init__(self, message, stage, **fields):
        super().__init__({**fields, "error": message, "stage": stage})


@dataclass(frozen=True)
class LocalUpload:
    path: Path
    name: str
    size: int
    sha256: str
    content_type: str


def plain_name(value):
    if not isinstance(value, str) or not value or value in (".", "..") or any(char in value for char in ("/", "\\", "\0")):
        raise FileTransferError("File name must be a plain filename", "prepare")
    return value


def inspect_upload(path):
    """Inspect a regular file and hash it in bounded chunks before any request."""
    try:
        if not isinstance(path, str) or not path:
            raise FileTransferError("Local source path must be a nonempty string", "read")
        source = Path(path).expanduser().resolve(strict=True)
        info = source.stat()
        if not stat.S_ISREG(info.st_mode):
            raise FileTransferError("Local source must be a regular file", "read")
        if info.st_size > MAX_FILE_BYTES:
            raise FileTransferError(f"Local file exceeds byte limit ({MAX_FILE_BYTES} bytes)", "read")
        size, digest = 0, hashlib.sha256()
        with source.open("rb") as stream:
            while chunk := stream.read(64 * 1024):
                size += len(chunk)
                if size > MAX_FILE_BYTES:
                    raise FileTransferError(f"Local file exceeds byte limit ({MAX_FILE_BYTES} bytes)", "read")
                digest.update(chunk)
            after = os.fstat(stream.fileno())
        if size != info.st_size or after.st_size != info.st_size or after.st_mtime_ns != info.st_mtime_ns:
            raise FileTransferError("Local file changed while being hashed", "read")
        content_type = mimetypes.guess_type(str(source))[0]
        if content_type is None:
            logger.warning("component=filestream operation=inspect unknown MIME type; using application/octet-stream")
            content_type = "application/octet-stream"
        return LocalUpload(source, source.name, size, digest.hexdigest(), content_type)
    except ToolFailure:
        raise
    except (OSError, ValueError, TypeError) as exc:
        raise FileTransferError(f"Local file inspection failed: {type(exc).__name__}", "read") from exc


def _expected(metadata):
    size, digest = metadata.get("size"), metadata.get("sha256")
    if isinstance(size, bool) or not isinstance(size, int) or not 0 <= size <= MAX_FILE_BYTES:
        raise FileTransferError("Phone file size is invalid or exceeds the 256 MiB limit", "command")
    if not isinstance(digest, str) or not _SHA256.fullmatch(digest):
        raise FileTransferError("Phone file SHA-256 is invalid", "command")
    marker = metadata.get("media")
    match = _MARKER.fullmatch(marker) if isinstance(marker, str) else None
    if match is None:
        raise FileTransferError("Phone file media token is invalid", "command")
    return size, digest.lower(), plain_name(metadata.get("name")), match.group(1)


def _destination(dest, name, environ):
    if dest is None:
        environment = os.environ if environ is None else environ
        directory = environment.get("ATOMIC_BRIDGE_PULL_DIR", "~/.hermes/workspace/phone-files/")
        if not isinstance(directory, str) or not directory:
            raise FileTransferError("ATOMIC_BRIDGE_PULL_DIR must name a directory", "prepare")
        directory = Path(directory).expanduser()
        directory.mkdir(parents=True, exist_ok=True)
        return (directory / name).absolute()
    if not isinstance(dest, str) or not dest:
        raise FileTransferError("dest must be a nonempty local file or directory path", "prepare")
    target = Path(dest).expanduser()
    if target.is_dir() or dest.endswith(os.sep):
        target.mkdir(parents=True, exist_ok=True)
        target /= name
    target.parent.mkdir(parents=True, exist_ok=True)
    return target.absolute()


def _remove_temporary(path):
    try:
        path.unlink(missing_ok=True)
    except OSError as exc:
        raise FileTransferError(f"Temporary file cleanup failed: {type(exc).__name__}", "cleanup") from exc


def pull_to_path(client, metadata, dest=None, *, environ=None):
    """Download to the destination's directory and publish only verified bytes."""
    temporary_path = None
    stage = "prepare"
    try:
        size, digest, name, token = _expected(metadata)
        target = _destination(dest, name, environ)
        with tempfile.NamedTemporaryFile(mode="w+b", dir=target.parent, prefix=".atomic-pull-", suffix=".tmp", delete=False) as sink:
            temporary_path = Path(sink.name)
            stage = "fetch"
            response = client.download(f"/media/{token}", sink, max_bytes=MAX_FILE_BYTES, timeout=300)
            value = response.value
            if response.status != 200 or "error" in value:
                fields = {"status": response.status} if response.status is not None else {}
                raise FileTransferError(value.get("error", "File media download failed"), stage, **fields)
            stage = "verify"
            if value.get("size") != size or sink.tell() != size:
                raise FileTransferError("Downloaded file size does not match the phone result", stage)
            if value.get("sha256") != digest:
                raise FileTransferError("Downloaded file SHA-256 does not match the phone result", stage)
            content_type = value.get("content_type")
            if not isinstance(content_type, str) or not content_type:
                raise FileTransferError("Downloaded file content type is missing", stage)
        stage = "commit"
        os.replace(temporary_path, target)
        temporary_path = None
        return {"path": str(target), "size": size, "sha256": digest, "content_type": content_type}
    except ToolFailure:
        raise
    except (OSError, ValueError, TypeError) as exc:
        raise FileTransferError(f"File download failed: {type(exc).__name__}", stage) from exc
    finally:
        if temporary_path is not None:
            _remove_temporary(temporary_path)
