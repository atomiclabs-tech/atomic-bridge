"""Compose the server's domain services and connected-session ownership."""

import asyncio
import time

from aiohttp import web

from atomic_relay.bridge.dispatch import CommandDispatcher
from atomic_relay.bridge.registry import DeviceRegistry
from atomic_relay.media.paths import MediaPaths
from atomic_relay.media.registry import MediaRegistry
from atomic_relay.notifications.buffer import NotificationBuffer
from atomic_relay.notifications.filter import PackageFilter
from atomic_relay.notifications.waiters import NotificationWaiters
from atomic_relay.sessions.authentication import Authentication
from atomic_relay.sessions.manager import SessionManager
from atomic_relay.sessions.pairing import PairingManager
from atomic_relay.sessions.ratelimit import RateLimiter


class RelayRuntime:
    def __init__(self, config, *, clock=time.monotonic):
        self.sessions = SessionManager(config.sessions_file, monotonic=clock)
        self.pairing = PairingManager(clock=clock)
        self.rate_limiter = RateLimiter(clock=clock)
        self.authentication = Authentication(self.sessions, self.pairing, self.rate_limiter)
        self.clients = {}
        self.registry = DeviceRegistry(clock=clock)
        self.dispatch = CommandDispatcher(self.registry)
        self.auth_timeout = 30.0
        self.pending_auth = 0
        self.trusted_proxies = config.trusted_proxies
        self.server_version = config.server_version
        self.unlock_pin_file = config.unlock_pin_file
        self.media = MediaRegistry(MediaPaths(config.media_allowed_roots, config.media_dir, config.media_max_bytes,
                                             sessions_file=config.sessions_file), config.media_ttl_seconds)
        self.notifications = NotificationBuffer()
        self.package_filter = PackageFilter(config.notification_allow, config.notification_deny)
        self.notification_waiters = NotificationWaiters(self.notifications, clock=clock)

    async def close_session_sockets(self, token):
        for socket, owner in tuple(self.clients.items()):
            if owner == token and not socket.closed:
                await asyncio.wait_for(socket.close(code=1008, message=b"Session revoked"), timeout=5)


RUNTIME = web.AppKey("runtime", RelayRuntime)
