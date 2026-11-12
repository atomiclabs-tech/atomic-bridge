# Ported from plugin/relay/auth.py at upstream commit da782a8.
"""Separate session and pairing failure buckets, keyed by client address."""

import logging
import time

logger = logging.getLogger("atomic_relay.sessions")


class RateLimiter:
    def __init__(self, *, clock=time.monotonic):
        self.clock = clock
        self._session = {}
        self._pairing = {}
        self._blocked = {}

    def _prune(self):
        now = self.clock()
        self._blocked = {ip: end for ip, end in self._blocked.items() if now < end}
        for bucket in (self._session, self._pairing):
            for ip in tuple(bucket):
                remaining = [stamp for stamp in bucket[ip] if stamp > now - 60]
                if remaining:
                    bucket[ip] = remaining
                else:
                    del bucket[ip]

    def is_blocked(self, ip: str) -> bool:
        self._prune()
        return ip in self._blocked

    def _record(self, ip, bucket, attempts, seconds, kind):
        self._prune()
        stamps = bucket.setdefault(ip, [])
        stamps.append(self.clock())
        if len(stamps) >= attempts:
            self._blocked[ip] = self.clock() + seconds
            del bucket[ip]
            logger.warning("IP %s blocked for %ds after %d failed %s auth attempts", ip, seconds, attempts, kind)

    def record_session_failure(self, ip: str) -> None:
        self._record(ip, self._session, 5, 300, "session")

    def record_pairing_failure(self, ip: str) -> None:
        self._record(ip, self._pairing, 10, 120, "pairing")

    def record_success(self, ip: str) -> None:
        self._session.pop(ip, None)
        self._pairing.pop(ip, None)
