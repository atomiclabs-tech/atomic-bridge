import logging

from atomic_relay.sessions.authentication import Authentication
from atomic_relay.sessions.manager import SessionManager
from atomic_relay.sessions.pairing import PairingManager
from atomic_relay.sessions.ratelimit import RateLimiter


def test_stale_session_plus_stale_refresh_count_twice_by_client_address(caplog, monkeypatch):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    limiter = RateLimiter(clock=lambda: 0)
    auth = Authentication(SessionManager(), PairingManager(), limiter)
    payload = {"session_token": "synthetic-stale", "refresh_token": "synthetic-refresh", "device_id": "device"}
    with caplog.at_level(logging.INFO):
        assert auth.authenticate(payload, "real-client") is None
        assert auth.authenticate(payload, "real-client") is None
        assert not limiter.is_blocked("real-client")
        assert auth.authenticate(payload, "real-client") is None
    assert limiter.is_blocked("real-client") and not limiter.is_blocked("local-tools")
    assert [row.message for row in caplog.records].count("Auth failed") == 3
    assert payload["session_token"] not in caplog.text and payload["refresh_token"] not in caplog.text


def test_session_then_refresh_then_pair_and_client_cannot_choose_policy():
    sessions, pairing, limiter = SessionManager(), PairingManager(), RateLimiter()
    auth = Authentication(sessions, pairing, limiter)
    assert pairing.register_code("ABC123", ttl_seconds=0, grants={"bridge": 0})
    session = auth.authenticate({"pairing_code": "ABC123", "device_id": "device", "ttl_seconds": 1,
                                 "grants": {"bridge": 1}}, "client", transport_hint="wss")
    assert session and not session.channel_is_expired("bridge")
    assert pairing.consume_code("ABC123") is None
    assert auth.authenticate({"session_token": session.token, "pairing_code": "bad"}, "client") is session
    renewed = auth.authenticate({"session_token": "synthetic-stale", "refresh_token": session.refresh_token,
                                 "device_id": "device"}, "client")
    assert renewed and renewed.token != session.token
    assert sessions.get_session(session.token)


def test_valid_old_session_adopts_refresh_and_empty_auth_counts():
    sessions, pairing, limiter = SessionManager(), PairingManager(), RateLimiter()
    auth = Authentication(sessions, pairing, limiter)
    old = sessions.create_session("phone", "device", ttl_seconds=0)
    assert not sessions.has_trusted_device("device")
    assert auth.authenticate({"session_token": old.token, "device_id": "device"}, "client") is old
    assert old.refresh_token and sessions.has_trusted_device("device")
    for _ in range(5):
        assert auth.authenticate({}, "empty-client") is None
    assert limiter.is_blocked("empty-client")
