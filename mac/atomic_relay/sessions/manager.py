# Ported from plugin/relay/auth.py at upstream commit da782a8.
"""Persist credential mutations and rotate trusted-device refresh secrets."""

import copy
import logging
import math
import secrets
import time
import uuid
from contextlib import contextmanager
from hashlib import sha256

from atomic_relay.sessions.grants import DEFAULT_TTL_SECONDS, REFRESH_TTL_SECONDS, duration, materialize, reduce_policy
from atomic_relay.sessions.models import Session, TrustedDevice
from atomic_relay.sessions.store import SessionStore

METADATA = ("transport_hint", "client_surface", "device_form_factor", "device_model", "device_platform", "supervised_mode")
logger = logging.getLogger("atomic_relay.sessions")


def refresh_hash(token: str) -> str:
    return sha256(token.encode("utf-8")).hexdigest()


class SessionManager:
    """Own sessions and trusted credentials; loading never writes the store."""

    def __init__(self, persistence_path=None, *, clock=time.time, monotonic=time.monotonic):
        self.clock = clock
        self.monotonic = monotonic
        self.store = SessionStore(persistence_path, clock=clock)
        sessions, devices = self.store.load()
        self._sessions = {item.token: item for item in sessions if not item.is_expired}
        self._trusted = {item.refresh_token_hash: item for item in devices if not item.is_expired}
        self._last_flush = monotonic()
        self._dirty = False

    @contextmanager
    def _mutation(self):
        previous = copy.deepcopy((self._sessions, self._trusted, self._dirty))
        try:
            yield
            self.store.save(self._sessions.values(), self._trusted.values())
        except (OSError, ValueError, TypeError):
            self._sessions, self._trusted, self._dirty = previous
            raise
        self._dirty = False
        self._last_flush = self.monotonic()

    def flush(self, *, force=False) -> None:
        if self._dirty and (force or self.monotonic() - self._last_flush >= 60):
            with self._mutation():
                pass

    def _cleanup(self):
        expired = [key for key, session in self._sessions.items() if session.is_expired]
        stale = [key for key, trusted in self._trusted.items() if trusted.is_expired]
        if expired or stale:
            with self._mutation():
                for key in expired:
                    del self._sessions[key]
                for key in stale:
                    del self._trusted[key]

    def create_session(self, device_name, device_id, ttl_seconds=None, grants=None,
                       issue_refresh_token=False, **metadata) -> Session:
        ttl = DEFAULT_TTL_SECONDS if ttl_seconds is None else duration(ttl_seconds)
        now = self.clock()
        policy = materialize(grants, ttl, now)
        session = Session(str(uuid.uuid4()), device_name, device_id, created_at=now, last_seen=now,
                          expires_at=math.inf if ttl == 0 else now + ttl, grants=policy,
                          first_seen=now, clock=self.clock, **metadata)
        with self._mutation():
            if issue_refresh_token:
                identity = device_id.strip()
                if identity and identity.casefold() not in {"unknown", "none", "null"}:
                    if (any(item.device_id == identity for item in self._sessions.values())
                            or any(item.device_id == identity for item in self._trusted.values())):
                        logger.warning("component=sessions operation=pair replaced existing trusted device device_id=%s", identity)
                    self._sessions = {key: item for key, item in self._sessions.items() if item.device_id != identity}
                    self._trusted = {key: item for key, item in self._trusted.items() if item.device_id != identity}
                self._issue_refresh(session, ttl, grants)
            self._sessions[session.token] = session
        return session

    def _issue_refresh(self, session, ttl, grants):
        token = secrets.token_urlsafe(32)
        hashed = refresh_hash(token)
        now = self.clock()
        self._trusted[hashed] = TrustedDevice(
            hashed, session.device_name, session.device_id, created_at=now, last_used=now,
            expires_at=now + REFRESH_TTL_SECONDS, session_ttl_seconds=ttl,
            grants=dict(grants) if grants is not None else None, clock=self.clock,
            **{key: getattr(session, key) for key in METADATA},
        )
        session.refresh_token = token
        return token

    def issue_refresh_token_for_session(self, session: Session) -> str:
        ttl = 0 if session.expires_at == math.inf else max(0, session.expires_at - self.clock())
        with self._mutation():
            token = self._issue_refresh(session, ttl, None)
        return token

    def has_trusted_device(self, device_id: str) -> bool:
        self._cleanup()
        return any(item.device_id == device_id for item in self._trusted.values())

    def refresh_session(self, refresh_token, *, device_name, device_id, **metadata) -> Session | None:
        self._cleanup()
        hashed = refresh_hash(refresh_token)
        trusted = self._trusted.get(hashed)
        if trusted is None:
            return None
        identity = device_id.strip()
        legacy = (trusted.client_surface in {"desktop", "unknown"}
                  and trusted.device_id.strip().casefold() in {"unknown", "none", "null"}
                  and identity.casefold() not in {"", "unknown", "none", "null"})
        if identity and identity != trusted.device_id and not legacy:
            return None
        now = self.clock()
        ttl = DEFAULT_TTL_SECONDS if trusted.session_ttl_seconds is None else trusted.session_ttl_seconds
        values = {key: getattr(trusted, key) for key in METADATA}
        values.update({key: value for key, value in metadata.items() if value is not None and value != "unknown"})
        session = Session(str(uuid.uuid4()), device_name or trusted.device_name,
                          identity if legacy else trusted.device_id, created_at=now, last_seen=now,
                          expires_at=math.inf if ttl == 0 else now + ttl,
                          grants=materialize(trusted.grants, ttl, now), first_seen=now,
                          clock=self.clock, **values)
        with self._mutation():
            del self._trusted[hashed]
            session.refresh_token = secrets.token_urlsafe(32)
            trusted.refresh_token_hash = refresh_hash(session.refresh_token)
            trusted.device_id, trusted.device_name, trusted.last_used = session.device_id, session.device_name, now
            trusted.expires_at = now + REFRESH_TTL_SECONDS
            for key in METADATA:
                setattr(trusted, key, getattr(session, key))
            self._trusted[trusted.refresh_token_hash] = trusted
            self._sessions[session.token] = session
        return session

    def get_session(self, token: str) -> Session | None:
        self._cleanup()
        session = self._sessions.get(token)
        if session is not None:
            session.last_seen = self.clock()
            self._dirty = True
            self.flush()
        return session

    def list_sessions(self) -> list[Session]:
        self._cleanup()
        return list(self._sessions.values())

    def find_by_prefix(self, prefix: str) -> list[Session]:
        return [item for item in self.list_sessions() if item.token.startswith(prefix)]

    def active_count(self) -> int:
        return len(self.list_sessions())

    def revoke_session(self, token: str) -> bool:
        target = self._sessions.get(token)
        if target is None:
            return False
        with self._mutation():
            del self._sessions[token]
            self._trusted = {key: item for key, item in self._trusted.items() if item.device_id != target.device_id}
        return True

    def update_session(self, token, ttl_seconds=None, grants=None) -> Session | None:
        self._cleanup()
        session = self._sessions.get(token)
        if session is None:
            return None
        now = self.clock()
        if ttl_seconds is not None:
            duration(ttl_seconds)
        expiry = session.expires_at if ttl_seconds is None else (math.inf if ttl_seconds == 0 else now + ttl_seconds)
        ttl = 0 if expiry == math.inf else max(0, expiry - now)
        policy = materialize(grants, ttl, now)
        with self._mutation():
            session.expires_at, session.grants, session.last_seen = expiry, policy, now
        return session

    def reduce_session_policy(self, token, ttl_seconds=None, grants=None) -> Session | None:
        self._cleanup()
        session = self._sessions.get(token)
        if session is None:
            return None
        with self._mutation():
            reduce_policy(session, ttl_seconds, grants, self.clock())
        return session

    def update_session_device_metadata(self, session, **metadata) -> None:
        changes = {}
        for key, value in metadata.items():
            if value is None:
                continue
            if key != "supervised_mode":
                if not isinstance(value, str):
                    raise ValueError("device metadata must be strings")
                value = value.strip()
                if not value or value == "unknown":
                    continue
            changes[key] = value
        records = [session, *(item for item in self._trusted.values() if item.device_id == session.device_id)]
        if any(getattr(record, key) != value for record in records for key, value in changes.items()):
            with self._mutation():
                for record in records:
                    for key, value in changes.items():
                        setattr(record, key, value)
