# Ported from plugin/relay/server.py at upstream commit da782a8.
"""Loopback pairing-code registration routes."""

import logging

from aiohttp import web

from atomic_relay.sessions.pairing import normalize_code
from atomic_relay.web.runtime import RUNTIME

logger = logging.getLogger("atomic_relay.web")
PAIRING_PATHS = {"/pairing/register", "/pairing/approve", "/pairing/check"}


async def register(request):
    try:
        payload = await request.json()
        if not isinstance(payload, dict):
            raise ValueError("body must be a JSON object")
        runtime = request.app[RUNTIME]
        valid = runtime.pairing.register_code(
            payload.get("code"), ttl_seconds=payload.get("ttl_seconds"),
            grants=payload.get("grants"), transport_hint=payload.get("transport_hint"),
        )
        if not valid:
            raise ValueError("invalid code format (must be 6 chars from A-Z / 0-9)")
    except (ValueError, TypeError):
        logger.exception("component=pairing operation=register invalid request")
        return web.json_response({"ok": False, "error": "invalid pairing request"}, status=400)
    return web.json_response({"ok": True, "code": payload["code"].strip().upper()})


async def check(request):
    try:
        payload = await request.json()
        if not isinstance(payload, dict):
            raise ValueError("body must be a JSON object")
        code = normalize_code(payload.get("code"))
    except (ValueError, TypeError):
        logger.exception("component=pairing operation=check invalid request")
        return web.json_response({"ok": False, "error": "invalid pairing request"}, status=400)
    return web.json_response({"ok": True, "pending": request.app[RUNTIME].pairing.validate_code(code)})


def add_routes(app):
    for path in PAIRING_PATHS - {"/pairing/check"}:
        app.router.add_post(path, register)
    app.router.add_post("/pairing/check", check)
