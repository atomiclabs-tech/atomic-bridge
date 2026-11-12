# Ported from plugin/relay/auth.py at upstream commit da782a8.
"""Single-use, ten-minute operator-approved pairing codes."""

import logging
import string
import time
from collections import deque
from dataclasses import dataclass

from atomic_relay.sessions.grants import duration, validate_grants

PAIRING_ABUSE_WINDOW_SECONDS = 600
PAIRING_ABUSE_MAX_FAILURES = 20
logger = logging.getLogger("atomic_relay.sessions.pairing")


def normalize_code(code: str) -> str:
    if not isinstance(code, str):
        raise ValueError("pairing code must be six A-Z / 0-9 characters")
    normalized = code.strip().upper()
    if len(normalized) != 6 or any(character not in string.ascii_uppercase + string.digits for character in normalized):
        raise ValueError("pairing code must be six A-Z / 0-9 characters")
    return normalized


@dataclass(frozen=True)
class PairingMetadata:
    ttl_seconds: float | None = None
    grants: dict[str, float] | None = None
    transport_hint: str | None = None


class PairingManager:
    def __init__(self, *, clock=time.monotonic):
        self.clock = clock
        self._codes = {}
        self._failures = deque()

    def register_code(self, code, ttl_seconds=None, grants=None, transport_hint=None) -> bool:
        try:
            normalized = normalize_code(code)
        except ValueError:
            return False
        if ttl_seconds is not None:
            duration(ttl_seconds, whole=True)
        policy = validate_grants(grants, whole=True) if grants is not None else None
        if transport_hint is not None and transport_hint not in {"ws", "wss", "unknown"}:
            raise ValueError("transport_hint must be ws/wss/unknown")
        self._codes[normalized] = (self.clock() + 600, PairingMetadata(ttl_seconds, policy, transport_hint))
        return True

    def _entry(self, code, consume):
        now = self.clock()
        self._codes = {key: entry for key, entry in self._codes.items() if now <= entry[0]}
        try:
            normalized = normalize_code(code)
        except ValueError:
            return None
        return self._codes.pop(normalized, None) if consume else self._codes.get(normalized)

    def validate_code(self, code) -> bool:
        return self._entry(code, False) is not None

    def consume_code(self, code) -> PairingMetadata | None:
        entry = self._entry(code, True)
        if entry is None:
            now = self.clock()
            while self._failures and self._failures[0] <= now - PAIRING_ABUSE_WINDOW_SECONDS:
                self._failures.popleft()
            self._failures.append(now)
            if len(self._failures) >= PAIRING_ABUSE_MAX_FAILURES:
                self._codes.clear()
                self._failures.clear()
                logger.warning("component=pairing operation=abuse-cap cleared pending codes after 20 failures")
        return entry[1] if entry is not None else None
