# Ported from plugin/relay/server.py at upstream commit da782a8.
"""Grant-checked websocket messages, isolated at the per-message boundary."""

import asyncio
import json
import logging
import time

from atomic_relay.bridge.status import derive_aliases
from atomic_relay.web.errors import sanitized_error
from atomic_relay.web.ws_auth import send_system

logger = logging.getLogger("atomic_relay")


async def on_message(socket, runtime, raw, unknown):
    try:
        envelope = json.loads(raw)
        if not isinstance(envelope, dict) or not isinstance(envelope.get("payload", {}), dict):
            raise ValueError("message requires object envelope and payload")
        channel, kind = envelope.get("channel"), envelope.get("type")
        if not isinstance(channel, str) or not isinstance(kind, str):
            raise ValueError("message channel and type must be strings")
        payload = envelope.get("payload", {})
        if channel == "system" and kind in {"ping", "pong"}:
            if kind == "ping":
                await send_system(socket, "pong", {"ts": payload.get("ts", time.time())}, envelope.get("id"))
            return
        if channel == "bridge" and kind in {"bridge.status", "bridge.response"}:
            session = runtime.sessions.get_session(runtime.clients.get(socket, ""))
            if session is None or session.channel_is_expired("bridge"):
                await send_system(socket, "error", {"message": "Bridge grant expired for this device"}, envelope.get("id"))
                return
            if kind == "bridge.response":
                runtime.dispatch.response(socket, payload)
            else:
                replaced = runtime.registry.update(socket, payload, session)
                if replaced is not None:
                    runtime.dispatch.detach(replaced, "Phone reconnected")
                    await asyncio.wait_for(replaced.close(code=1001, message=b"Device reconnected"), timeout=5)
            return
        if channel == "notifications" and kind in {"notification.posted", "notification.removed"}:
            session = runtime.sessions.get_session(runtime.clients.get(socket, ""))
            if session is None or session.channel_is_expired("bridge"):
                return
            if not runtime.package_filter.keeps(payload.get("package_name")):
                logger.debug("component=notifications operation=filter dropped")
                return
            aliases = derive_aliases(session.device_id, session.device_name, session.client_surface, session.device_form_factor)
            entry = runtime.notifications.append(session.device_id, kind, payload, aliases=aliases)
            runtime.notification_waiters.publish(entry)
            return
        if channel not in unknown:
            unknown.add(channel)
            logger.debug("component=websocket operation=dispatch unknown channel or type")
            await send_system(socket, "error", {"message": "Unknown channel"}, envelope.get("id"))
    except Exception as error:
        logger.exception("component=websocket operation=message failure_type=%s",
                         type(error).__name__, exc_info=sanitized_error(error))
        await send_system(socket, "error", {"message": "Invalid message"})
