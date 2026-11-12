# Ported from plugin/relay/channels/bridge.py at upstream commit da782a8.
"""Send each command once and correlate a response to its receiving socket."""

import asyncio
import json
import logging
import uuid

from atomic_relay.bridge.registry import BridgeError

logger = logging.getLogger("atomic_relay.bridge")
RESPONSE_TIMEOUT = 30.0
COMMAND_TIMEOUTS = {"/call_audio_probe": 119.0, "/files/pull": 290.0, "/files/push": 290.0}


class CommandDispatcher:
    def __init__(self, registry, *, timeout=RESPONSE_TIMEOUT, send_timeout=5, command_timeouts=None):
        self.registry, self.timeout, self.send_timeout = registry, timeout, send_timeout
        self.command_timeouts = dict(COMMAND_TIMEOUTS if command_timeouts is None else command_timeouts)
        self.pending = {}

    async def command(self, method, path, params, body, selector=None):
        client, _ = self.registry.resolve(selector)
        identity = str(uuid.uuid4())
        future = asyncio.get_running_loop().create_future()
        self.pending[identity] = (client.socket, future)
        envelope = {"channel": "bridge", "type": "bridge.command", "id": identity,
                    "payload": {"request_id": identity, "method": method, "path": path, "params": params, "body": body}}
        try:
            try:
                await asyncio.wait_for(client.socket.send_str(json.dumps(envelope)), timeout=self.send_timeout)
            except (OSError, RuntimeError, TimeoutError) as error:
                logger.exception("component=bridge operation=send device_id=%s request_id=%s failure_type=%s",
                             client.device_id, identity, type(error).__name__,
                             exc_info=(RuntimeError, RuntimeError("phone send failed"), error.__traceback__))
                raise BridgeError("Failed to send command to phone", 502) from error
            try:
                return await asyncio.wait_for(future, timeout=self.command_timeouts.get(path, self.timeout))
            except TimeoutError as error:
                logger.error("component=bridge operation=wait device_id=%s request_id=%s",
                             client.device_id, identity, exc_info=True)
                raise BridgeError("Phone did not respond before the command timeout", 504) from error
        finally:
            self.pending.pop(identity, None)
            if not future.done():
                future.cancel()
            elif not future.cancelled():
                future.exception()

    def response(self, socket, payload):
        identity = payload.get("request_id")
        if not isinstance(identity, str):
            raise ValueError("response requires a request id")
        pending = self.pending.get(identity)
        if pending is None or pending[0] is not socket or pending[1].done():
            return
        status = payload.get("status")
        if isinstance(status, bool) or not isinstance(status, int) or not 200 <= status <= 599 or "result" not in payload:
            raise ValueError("response requires an HTTP status and result")
        pending[1].set_result(payload)

    def detach(self, socket, reason="Phone disconnected"):
        for owner, future in tuple(self.pending.values()):
            if owner is socket and not future.done():
                future.set_exception(BridgeError(reason, 502))

    def close(self):
        for _, future in tuple(self.pending.values()):
            if not future.done():
                future.set_exception(BridgeError("Relay server shutting down", 502))
