# Ported from plugin/relay/server.py at upstream commit da782a8.
"""First-text authentication deadline and the frozen system envelope."""

import asyncio
import json
import logging
import math
import uuid

from aiohttp import WSMsgType

from atomic_relay.web.errors import log_failure

logger = logging.getLogger("atomic_relay")


async def send_system(socket, kind, payload, identity=None):
    envelope = {"channel": "system", "type": kind, "id": identity or str(uuid.uuid4()), "payload": payload}
    await asyncio.wait_for(socket.send_json(envelope), timeout=5)


async def _first_text(socket):
    async for message in socket:
        if message.type == WSMsgType.TEXT:
            return message.data
        if message.type == WSMsgType.ERROR:
            raise ConnectionError("websocket receive failed")
    return None


def auth_payload(session, version):
    def normalize(value):
        return None if math.isinf(value) else value
    result = {"session_token": session.token, "server_version": version, "profiles": [],
              "expires_at": normalize(session.expires_at),
              "grants": {key: normalize(value) for key, value in session.grants.items()},
              "transport_hint": session.transport_hint, "client_surface": session.client_surface,
              "device_form_factor": session.device_form_factor}
    if session.refresh_token:
        result["refresh_token"] = session.refresh_token
    return result


async def authenticate(socket, runtime, address, transport):
    try:
        raw = await asyncio.wait_for(_first_text(socket), timeout=runtime.auth_timeout)
    except TimeoutError as error:
        log_failure(logger, error, "component=websocket operation=authenticate deadline")
        logger.info("Auth failed")
        await send_system(socket, "auth.fail", {"reason": "Authentication timeout"})
        return None
    if raw is None:
        return None
    try:
        envelope = json.loads(raw)
        if not isinstance(envelope, dict):
            raise ValueError("authentication envelope must be an object")
        if envelope.get("channel") != "system" or envelope.get("type") != "auth":
            logger.info("Auth failed")
            await send_system(socket, "auth.fail", {"reason": "First message must be system/auth"})
            return None
        payload = envelope.get("payload", {})
        if not isinstance(payload, dict):
            raise ValueError("authentication payload must be an object")
        session = runtime.authentication.authenticate(payload, address, transport_hint=transport)
    except ValueError as error:
        log_failure(logger, error, "component=websocket operation=authenticate invalid frame")
        logger.info("Auth failed")
        await send_system(socket, "auth.fail", {"reason": "Invalid message format"})
        return None
    if session is None:
        await send_system(socket, "auth.fail", {"reason": "Invalid pairing code or session token"})
        return None
    await send_system(socket, "auth.ok", auth_payload(session, runtime.server_version))
    return session
