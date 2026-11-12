import math

import pytest

from atomic_relay.sessions.grants import DEFAULT_TTL_SECONDS, duration, materialize, reduce_policy, validate_grants
from atomic_relay.sessions.models import Session


def test_default_grants_equal_session_expiry_and_explicit_values_are_clamped():
    assert materialize(None, 0, 100)["bridge"] == math.inf
    assert materialize({"bridge": 0, "custom": 20}, 10, 100)["bridge"] == 110
    assert materialize({"custom": 20}, 0, 100)["custom"] == 120
    grants = materialize(None, 30 * 86400, 100)
    assert set(grants) == {"chat", "terminal", "bridge", "tui", "voice:config", "voice:stt", "voice:tts", "voice:realtime"}
    assert grants["bridge"] == 100 + 30 * 86400
    assert all(expiry == 100 + 30 * 86400 for expiry in grants.values())
    assert materialize({"bridge": 7 * 86400}, 30 * 86400, 100)["bridge"] == 100 + 7 * 86400


@pytest.mark.parametrize("ttl", [0, 1, DEFAULT_TTL_SECONDS, 2 * DEFAULT_TTL_SECONDS])
def test_every_default_channel_tracks_short_long_and_never_session_ttls(ttl):
    expiry = math.inf if ttl == 0 else 100 + ttl
    assert all(value == expiry for value in materialize(None, ttl, 100).values())


def test_new_default_grants_still_only_reduce_and_clamp_to_shorter_session():
    now = 100
    session = Session("synthetic", "phone", "device", created_at=now)
    reduce_policy(session, None, {"bridge": 8 * 86400}, now)
    assert session.grants["bridge"] == now + 8 * 86400
    original = session.expires_at, dict(session.grants), session.last_seen
    for ttl, grants in ((0, None), (None, {"bridge": 0}), (None, {"bridge": 9 * 86400})):
        with pytest.raises(PermissionError):
            reduce_policy(session, ttl, grants, now)
        assert (session.expires_at, session.grants, session.last_seen) == original
    reduce_policy(session, 2 * 86400, None, now)
    assert all(value == session.expires_at == now + 2 * 86400 for value in session.grants.values())


@pytest.mark.parametrize("value", [True, "never", -1, math.inf, math.nan, None])
def test_invalid_durations_fail(value):
    with pytest.raises(ValueError):
        duration(value)


def test_whole_seconds_and_grants_shape():
    with pytest.raises(ValueError):
        duration(1.5, whole=True)
    with pytest.raises(ValueError):
        validate_grants([])
    with pytest.raises(ValueError):
        validate_grants({1: 2})


def test_reduce_validates_every_change_atomically():
    session = Session("synthetic", "phone", "device", expires_at=200, grants={"bridge": 180}, created_at=100)
    with pytest.raises(PermissionError):
        reduce_policy(session, 50, {"bridge": 100}, 100)
    assert session.expires_at == 200 and session.grants == {"bridge": 180}
    with pytest.raises(ValueError):
        reduce_policy(session, None, {"unknown": 1}, 100)
    reduce_policy(session, 50, None, 100)
    assert session.expires_at == 150 and session.grants == {"bridge": 150}
