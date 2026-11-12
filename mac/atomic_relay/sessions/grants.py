# Ported from plugin/relay/auth.py at upstream commit da782a8.
"""Convert relative grant durations to session-bounded expiries."""

import math

DEFAULT_TTL_SECONDS = 30 * 24 * 3600
REFRESH_TTL_SECONDS = 180 * 24 * 3600
DEFAULT_CHANNELS = ("chat", "terminal", "bridge", "tui", "voice:config", "voice:stt", "voice:tts", "voice:realtime")


def duration(value: object, *, whole: bool = False) -> float:
    """Validate nonnegative numeric seconds without including input in errors."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("duration must be a non-negative number")
    if not math.isfinite(value) or value < 0 or (whole and value != int(value)):
        raise ValueError("duration must be finite non-negative seconds")
    return value


def validate_grants(value: object, *, whole: bool = False) -> dict[str, float]:
    if not isinstance(value, dict) or any(not isinstance(key, str) for key in value):
        raise ValueError("grants must map channel names to durations")
    return {key: duration(seconds, whole=whole) for key, seconds in value.items()}


def materialize(grants: dict[str, float] | None, ttl: float, now: float) -> dict[str, float]:
    duration(ttl)
    expiry = math.inf if ttl == 0 else now + ttl
    result = {key: expiry for key in DEFAULT_CHANNELS}
    for key, seconds in validate_grants(grants).items() if grants is not None else ():
        result[key] = min(math.inf if seconds == 0 else now + seconds, expiry)
    return result


def reduce_policy(session, ttl: float | None, grants: dict[str, float] | None, now: float) -> None:
    """Validate every requested reduction before changing any session field."""
    expiry = session.expires_at
    if ttl is not None:
        duration(ttl)
        expiry = math.inf if ttl == 0 else now + ttl
        if expiry > session.expires_at:
            raise PermissionError("operator approval required to extend session")
    requested = {}
    for key, seconds in validate_grants(grants).items() if grants is not None else ():
        if key not in session.grants:
            raise ValueError("unknown grant")
        candidate = math.inf if seconds == 0 else now + seconds
        if candidate > session.grants[key]:
            raise PermissionError("operator approval required to expand grant")
        requested[key] = candidate
    session.expires_at = expiry
    session.grants = {key: min(requested.get(key, value), expiry) for key, value in session.grants.items()}
    session.last_seen = now
