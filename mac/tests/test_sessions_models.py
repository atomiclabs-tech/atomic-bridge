import math

from atomic_relay.sessions.models import Session, TrustedDevice


def test_models_expiry_grants_defaults_and_safe_repr():
    now = [1000]
    session = Session("synthetic-secret", "phone", "device", created_at=now[0], clock=lambda: now[0])
    assert session.expires_at == 1000 + 30 * 86400
    assert session.first_seen == 1000
    assert session.channel_expires_at("bridge") == session.expires_at
    assert session.channel_is_expired("unknown")
    assert not session.is_expired
    session.refresh_token = "synthetic-refresh"
    assert "synthetic-secret" not in repr(session)
    assert "synthetic-refresh" not in repr(session)
    now[0] = session.expires_at + 1
    assert session.is_expired and session.channel_is_expired("bridge")
    session = Session("synthetic", "phone", "device", expires_at=math.inf)
    assert not session.is_expired
    assert all(value == math.inf for value in session.grants.values())


def test_trusted_expiry_and_safe_repr():
    now = [1000]
    device = TrustedDevice("synthetic-hash", "phone", "device", created_at=1000, clock=lambda: now[0])
    assert device.expires_at == 1000 + 180 * 86400
    assert "synthetic-hash" not in repr(device)
    now[0] = device.expires_at + 1
    assert device.is_expired
