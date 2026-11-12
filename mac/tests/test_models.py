import math

from atomic_relay.sessions.models import Session, TrustedDevice


def test_expiry_boundary_and_missing_channel_fail_closed():
    now = [100]
    session = Session("synthetic", "phone", "device", created_at=50, expires_at=100,
                      grants={"bridge": 100, "custom": math.inf}, clock=lambda: now[0])
    trusted = TrustedDevice("synthetic", "phone", "device", created_at=50, expires_at=100, clock=lambda: now[0])
    assert not session.is_expired and not trusted.is_expired
    assert not session.channel_is_expired("bridge") and session.channel_is_expired("missing")
    now[0] += 0.001
    assert session.is_expired and trusted.is_expired and session.channel_is_expired("bridge")
    assert not session.channel_is_expired("custom")
