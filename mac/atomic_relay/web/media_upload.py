# Ported from plugin/relay/server.py at upstream commit da782a8.
"""Multipart upload adapter with one bounded chunk resident at a time."""

import asyncio
import logging

from aiohttp import ClientPayloadError, web

from atomic_relay.media.files import CHUNK_SIZE, UploadFile
from atomic_relay.media.paths import MediaError
from atomic_relay.web.errors import log_failure
from atomic_relay.web.media_authorization import authorize
from atomic_relay.web.runtime import RUNTIME

logger = logging.getLogger("atomic_relay.media")


def public_entry(entry, *, sensitive=False):
    result = {"ok": True, "token": entry.token, "expires_at": entry.expires_at, "size": entry.size, "sha256": entry.sha256}
    if sensitive:
        result["sensitive"] = entry.sensitive
    return result


async def upload(request):
    authorize(request)
    runtime = request.app[RUNTIME]
    try:
        if request.content_type != "multipart/form-data":
            raise MediaError("expected multipart/form-data")
        reader = await asyncio.wait_for(request.multipart(), timeout=30)
        field = await asyncio.wait_for(reader.next(), timeout=30)
        while field is not None and field.name != "file":
            field = await asyncio.wait_for(reader.next(), timeout=30)
        if field is None:
            raise MediaError("missing file multipart field")
        content_type = field.headers.get("Content-Type", "application/octet-stream")
        with UploadFile(runtime.media.paths.media_dir, runtime.media.paths.max_bytes) as output:
            while chunk := await asyncio.wait_for(field.read_chunk(CHUNK_SIZE), timeout=30):
                output.write(chunk)
            output.finish()
            entry = runtime.media.register(output.path, content_type, owned_file=True, upload=True)
            if entry.size != output.size or entry.sha256 != output.digest.hexdigest():
                raise MediaError("upload integrity verification failed", 409)
            output.committed = True
        return web.json_response(public_entry(entry))
    except (MediaError, ClientPayloadError, OSError, TimeoutError, ValueError) as error:
        log_failure(logger, error, "component=media operation=upload")
        status = error.status if isinstance(error, MediaError) else 400
        message = str(error) if isinstance(error, MediaError) else "media upload failed"
        return web.json_response({"ok": False, "error": message}, status=status)
