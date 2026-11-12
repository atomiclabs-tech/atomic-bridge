# Ported from plugin/relay/auth.py at upstream commit da782a8.
"""In-memory credentials; secret fields never participate in repr."""

import time
import math
from collections.abc import Callable
from dataclasses import dataclass, field

from atomic_relay.sessions.grants import DEFAULT_TTL_SECONDS, REFRESH_TTL_SECONDS, materialize


@dataclass
class Session:
    token: str = field(repr=False)
    device_name: str
    device_id: str
    created_at: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)
    expires_at: float = 0
    grants: dict[str, float] = field(default_factory=dict)
    transport_hint: str = "unknown"
    client_surface: str = "unknown"
    device_form_factor: str = "unknown"
    device_model: str = "unknown"
    device_platform: str = "unknown"
    first_seen: float = 0
    supervised_mode: dict | None = None
    refresh_token: str | None = field(default=None, repr=False, compare=False)
    clock: Callable[[], float] = field(default=time.time, repr=False, compare=False)

    def __post_init__(self):
        if self.expires_at == 0:
            self.expires_at = self.created_at + DEFAULT_TTL_SECONDS
        if self.first_seen == 0:
            self.first_seen = self.created_at
        if not self.grants:
            ttl = 0 if self.expires_at == math.inf else self.expires_at - self.created_at
            self.grants = materialize(None, ttl, self.created_at)

    @property
    def is_expired(self) -> bool:
        return self.clock() > self.expires_at

    def channel_expires_at(self, channel: str) -> float | None:
        return self.grants.get(channel)

    def channel_is_expired(self, channel: str) -> bool:
        expiry = self.channel_expires_at(channel)
        return expiry is None or self.clock() > expiry


@dataclass
class TrustedDevice:
    refresh_token_hash: str = field(repr=False)
    device_name: str
    device_id: str
    created_at: float = field(default_factory=time.time)
    last_used: float = field(default_factory=time.time)
    expires_at: float = 0
    session_ttl_seconds: float | None = None
    grants: dict[str, float] | None = None
    transport_hint: str = "unknown"
    client_surface: str = "unknown"
    device_form_factor: str = "unknown"
    device_model: str = "unknown"
    device_platform: str = "unknown"
    supervised_mode: dict | None = None
    clock: Callable[[], float] = field(default=time.time, repr=False, compare=False)

    def __post_init__(self):
        if self.expires_at == 0:
            self.expires_at = self.created_at + REFRESH_TTL_SECONDS

    @property
    def is_expired(self) -> bool:
        return self.clock() > self.expires_at
