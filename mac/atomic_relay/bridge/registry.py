# Ported from plugin/relay/channels/bridge.py at upstream commit da782a8.
"""Ready devices, selector aliases and active/most-recent routing."""

import logging
import time

from atomic_relay.bridge.status import BridgeClient, derive_aliases, normalize_selector

logger = logging.getLogger("atomic_relay.bridge")


class BridgeError(Exception):
    def __init__(self, message, status_code):
        super().__init__(message)
        self.status_code = status_code


class DeviceRegistry:
    def __init__(self, *, clock=time.monotonic):
        self.clock = clock
        self.clients = {}
        self.by_socket = {}
        self.active_device_id = None
        self.most_recent_device_id = None
        self.latest = None

    def update(self, socket, payload, session):
        """Register only a ready status frame; return the replaced socket, if any."""
        existing = self.clients.get(session.device_id)
        replaced = existing.socket if existing is not None and existing.socket is not socket else None
        if replaced is not None:
            self.by_socket.pop(replaced, None)
        client = BridgeClient(
            session.device_id, session.device_name, socket, self.clock(), dict(payload), session.token[:8],
            session.client_surface, session.device_form_factor,
            derive_aliases(session.device_id, session.device_name, session.client_surface, session.device_form_factor),
        )
        self.clients[client.device_id] = client
        self.by_socket[socket] = client.device_id
        self.latest = client
        self.most_recent_device_id = client.device_id
        if not self._connected(self.active_device_id):
            self.active_device_id = client.device_id
        return replaced

    def _connected(self, identity):
        client = self.clients.get(identity)
        return client is not None and client.connected

    def is_phone_connected(self):
        return any(client.connected for client in self.clients.values())

    def resolve(self, selector=None, *, require_connected=True):
        clients = [client for client in self.clients.values() if client.connected or not require_connected]
        if selector and selector.strip():
            raw = selector.strip()
            normalized = normalize_selector(raw)
            matches = [client for client in clients if raw in client.aliases or normalized in client.aliases]
            if not matches:
                raise BridgeError(f"Unknown bridge device selector: {raw}", 404)
            if len(matches) > 1:
                raise BridgeError(f"Ambiguous bridge device selector: {raw}", 409)
            return matches[0], raw
        for identity in (self.active_device_id, self.most_recent_device_id):
            if self._connected(identity):
                return self.clients[identity], None
        if clients:
            return max(clients, key=lambda item: item.last_seen_at), None
        raise BridgeError("no phone connected", 503)

    def status_payload(self, selector=None):
        try:
            client, _ = self.resolve(selector, require_connected=False)
        except BridgeError as error:
            if selector or error.status_code != 503 or self.latest is None:
                raise
            client = self.latest
        return client.status_payload(self.clock(), active=client.device_id == self.active_device_id)

    def devices_payload(self):
        clients = sorted(self.clients.values(), key=lambda item: item.last_seen_at, reverse=True)
        return {
            "active_device_id": self.active_device_id, "most_recent_device_id": self.most_recent_device_id,
            "devices": [item.status_payload(self.clock(), active=item.device_id == self.active_device_id) for item in clients],
            "count": len(clients),
        }

    def select_active(self, selector):
        client, alias = self.resolve(selector)
        self.active_device_id = client.device_id
        self.latest = client
        return {"ok": True, "active_device_id": client.device_id, "device_id": client.device_id,
                "device_name": client.device_name, "alias_used": alias, "aliases": sorted(client.aliases)}

    def detach(self, socket, reason):
        identity = self.by_socket.pop(socket, None)
        client = self.clients.get(identity)
        if client is None or client.socket is not socket:
            return
        del self.clients[identity]
        logger.info("bridge: detached device %s (%s)", identity, reason)
        if self.active_device_id == identity:
            self.active_device_id = None
        if self.most_recent_device_id == identity:
            self.most_recent_device_id = None
        connected = [item for item in self.clients.values() if item.connected]
        if connected:
            newest = max(connected, key=lambda item: item.last_seen_at)
            self.most_recent_device_id = newest.device_id
            if self.active_device_id is None:
                self.active_device_id = newest.device_id
            self.latest = newest
