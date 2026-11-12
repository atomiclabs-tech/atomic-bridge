# Ported from plugin/relay/server.py at upstream commit da782a8.
"""Local registration and authorized bounded media downloads."""

import asyncio
import logging
from pathlib import Path
from urllib.parse import quote

from aiohttp import web

from atomic_relay.media.files import CHUNK_SIZE, image_type
from atomic_relay.media.paths import MediaError
from atomic_relay.web.errors import log_failure
from atomic_relay.web.media_authorization import authorize
from atomic_relay.web.media_upload import public_entry, upload
from atomic_relay.web.runtime import RUNTIME

logger = logging.getLogger("atomic_relay.media")
MEDIA_PATHS = {"/media/register", "/media/{token}/sensitive"}


def _error(error, operation):
    log_failure(logger, error, f"component=media operation={operation}")
    status = error.status if isinstance(error, MediaError) else 400
    return web.json_response({"ok": False, "error": str(error) if isinstance(error, MediaError) else "media operation failed"}, status=status)


async def register(request):
    try:
        payload = await asyncio.wait_for(request.json(), timeout=30)
        if not isinstance(payload, dict):
            raise MediaError("registration body must be a JSON object")
        entry = request.app[RUNTIME].media.register(
            payload.get("path"), payload.get("content_type"), payload.get("file_name"),
            payload.get("sensitive", False), payload.get("owned_file", False),
        )
    except (ValueError, OSError, TimeoutError) as error:
        return _error(error, "register")
    return web.json_response(public_entry(entry, sensitive=True))


async def mark_sensitive(request):
    if not request.app[RUNTIME].media.mark_sensitive(request.match_info["token"]):
        raise web.HTTPNotFound(text="media not found or expired")
    return web.json_response({"ok": True})


async def download(request):
    authorize(request)
    registry = request.app[RUNTIME].media
    entry = registry.get(request.match_info["token"])
    if entry is None:
        raise web.HTTPNotFound(text="media not found or expired")
    response = None
    try:
        with registry.open(entry) as source:
            content_type = image_type(source, entry.content_type)
            if content_type != entry.content_type:
                logger.warning("component=media operation=download image content type corrected")
            headers = {"Content-Type": content_type, "Content-Length": str(entry.size), "Cache-Control": "private",
                       "Content-Disposition": "inline"}
            if entry.file_name:
                headers["Content-Disposition"] += "; filename*=UTF-8''" + quote(Path(entry.file_name).name, safe="")
            if entry.sensitive:
                headers["X-Media-Sensitive"] = "1"
            response = web.StreamResponse(headers=headers)
            await asyncio.wait_for(response.prepare(request), timeout=30)
            remaining = entry.size
            while remaining:
                chunk = source.read(min(CHUNK_SIZE, remaining))
                if not chunk:
                    raise OSError("media transfer ended before its registered size")
                await asyncio.wait_for(response.write(chunk), timeout=30)
                remaining -= len(chunk)
            await asyncio.wait_for(response.write_eof(), timeout=30)
            return response
    except (MediaError, OSError, TimeoutError) as error:
        if response is not None and response.prepared:
            log_failure(logger, error, "component=media operation=download interrupted")
            response.force_close()
            return response
        return _error(error, "download")


def add_routes(app):
    app.router.add_post("/media/register", register)
    app.router.add_post("/media/upload", upload)
    app.router.add_post("/media/{token}/sensitive", mark_sensitive)
    app.router.add_get("/media/{token}", download)
