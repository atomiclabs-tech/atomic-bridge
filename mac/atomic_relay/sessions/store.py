# Ported from plugin/relay/auth.py at upstream commit da782a8.
"""Read upstream version-1 credentials and atomically replace mutations."""

import json
import logging
import math
import os
import tempfile
import time
from dataclasses import fields
from pathlib import Path

from atomic_relay.sessions.models import Session, TrustedDevice

logger = logging.getLogger("atomic_relay.sessions")
OMITTED = {"clock", "refresh_token", "supervised_mode"}


def _number(value):
    if value == "never":
        return math.inf
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError("invalid sessions numeric field")
    return value


def _decode(payload, model, clock):
    if not isinstance(payload, dict):
        raise ValueError("invalid sessions entry")
    secret = "token" if model is Session else "refresh_token_hash"
    for key in (secret, "device_name", "device_id"):
        if not isinstance(payload.get(key), str) or (key != "device_name" and not payload[key]):
            raise ValueError("missing sessions identity field")
    values = dict(payload)
    for key in ("created_at", "last_seen" if model is Session else "last_used", "expires_at"):
        values[key] = _number(values[key])
    if model is Session:
        values["first_seen"] = _number(values.get("first_seen", values["created_at"]))
    elif values.get("session_ttl_seconds") is not None:
        values["session_ttl_seconds"] = _number(values["session_ttl_seconds"])
    if values.get("grants") is not None:
        if not isinstance(values["grants"], dict):
            raise ValueError("invalid sessions grants")
        values["grants"] = {key: _number(value) for key, value in values["grants"].items()}
    allowed = {item.name for item in fields(model)} - {"clock", "refresh_token"}
    if values.keys() - allowed:
        raise ValueError("unknown sessions entry field")
    return model(**values, clock=clock)


def _encode(record):
    result = {}
    for item in fields(record):
        if item.name in OMITTED:
            continue
        value = getattr(record, item.name)
        if item.name == "grants":
            if value is None:
                continue
            value = {key: "never" if number == math.inf else number for key, number in value.items()}
        elif value == math.inf:
            value = "never"
        result[item.name] = value
    if record.supervised_mode is not None:
        result["supervised_mode"] = record.supervised_mode
    return result


class SessionStore:
    """Loading is read-only; malformed data and every write failure are fatal."""

    def __init__(self, path: Path | None, *, clock=time.time):
        self.path = Path(path) if path is not None else None
        self.clock = clock

    def load(self) -> tuple[list[Session], list[TrustedDevice]]:
        if self.path is None or not self.path.exists():
            return [], []
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            if (not isinstance(data, dict) or type(data.get("version")) is not int
                    or data["version"] != 1 or not isinstance(data.get("sessions"), list)
                    or not isinstance(data.get("trusted_devices"), list)):
                raise ValueError("invalid version-1 sessions structure")
            sessions = [_decode(item, Session, self.clock) for item in data["sessions"]]
            devices = [_decode(item, TrustedDevice, self.clock) for item in data["trusted_devices"]]
            if len({item.token for item in sessions}) != len(sessions):
                raise ValueError("duplicate sessions identity")
            if len({item.refresh_token_hash for item in devices}) != len(devices):
                raise ValueError("duplicate trusted identity")
            return sessions, devices
        except (OSError, ValueError, TypeError, KeyError):
            logger.exception("component=sessions operation=load path=%s", self.path)
            raise

    def save(self, sessions, devices) -> None:
        if self.path is None:
            return
        temporary = None
        try:
            blob = json.dumps({"version": 1, "sessions": [_encode(item) for item in sessions],
                               "trusted_devices": [_encode(item) for item in devices]}, indent=2, allow_nan=False)
            self.path.parent.mkdir(parents=True, exist_ok=True)
            fd, name = tempfile.mkstemp(prefix=".atomic-sessions-", suffix=".tmp", dir=self.path.parent)
            temporary = Path(name)
            with os.fdopen(fd, "w", encoding="utf-8") as output:
                os.fchmod(output.fileno(), 0o600)
                output.write(blob)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.path)
            temporary = None
        except (OSError, ValueError, TypeError):
            logger.exception("component=sessions operation=save path=%s", self.path)
            raise
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
