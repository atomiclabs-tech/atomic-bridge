# Ported from plugin/relay/server.py at upstream commit da782a8.
"""Websocket upgrade, authenticated connection lifecycle and disconnect."""

import asyncio
import logging

from aiohttp import WSMsgType, web

from atomic_relay.web.address import client_address, transport_hint
from atomic_relay.web.errors import log_failure, sanitized_error
from atomic_relay.web.guard import refuse_origin
from atomic_relay.web.runtime import RUNTIME
from atomic_relay.web.ws_auth import authenticate, send_system
from atomic_relay.web.ws_channels import on_message

logger = logging.getLogger("atomic_relay")
MAX_PENDING_AUTH = 16


async def handle_ws(request):
    refusal = refuse_origin(request)
    if refusal is not None:
        return refusal
    runtime = request.app[RUNTIME]
    if runtime.pending_auth >= MAX_PENDING_AUTH:
        logger.warning("component=websocket operation=upgrade refused pending=%s", runtime.pending_auth)
        raise web.HTTPServiceUnavailable(text="Too many pending authentication sockets")
    address = client_address(request, runtime.trusted_proxies)
    if runtime.rate_limiter.is_blocked(address):
        logger.warning("WebSocket from blocked IP %s — rejecting", address)
        raise web.HTTPTooManyRequests(text="Too many failed authentication attempts. Try again later.")
    socket = web.WebSocketResponse(heartbeat=30, max_msg_size=4 * 1024 * 1024)
    runtime.pending_auth += 1
    pending = True
    try:
        await asyncio.wait_for(socket.prepare(request), timeout=5)
        transport = transport_hint(request, runtime.trusted_proxies)
        session = await authenticate(socket, runtime, address, transport)
        runtime.pending_auth -= 1
        pending = False
        if session is None:
            return socket
        runtime.clients[socket] = session.token
        unknown = set()
        # Heartbeat bounds detection of a failed peer while healthy idle sockets stay connected.
        async for message in socket:
            if message.type == WSMsgType.TEXT:
                await on_message(socket, runtime, message.data, unknown)
            elif message.type == WSMsgType.ERROR:
                error = socket.exception() or ConnectionError("websocket receive failed")
                log_failure(logger, error, "component=websocket operation=receive")
                break
    except Exception as error:
        logger.exception("component=websocket operation=connection failure_type=%s",
                         type(error).__name__, exc_info=sanitized_error(error))
        if not socket.prepared:
            raise
        if not socket.closed:
            await send_system(socket, "auth.fail", {"reason": "Connection failed"})
    finally:
        if pending:
            runtime.pending_auth -= 1
        runtime.clients.pop(socket, None)
        runtime.registry.detach(socket, f"client {address} disconnected")
        runtime.dispatch.detach(socket)
        if socket.prepared:
            await asyncio.wait_for(socket.close(), timeout=5)
    return socket
