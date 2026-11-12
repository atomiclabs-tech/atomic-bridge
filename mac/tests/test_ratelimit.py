from atomic_relay.sessions.ratelimit import RateLimiter


def test_session_window_and_five_minute_block_are_address_specific():
    now = [0]
    limiter = RateLimiter(clock=lambda: now[0])
    for _ in range(4):
        limiter.record_session_failure("client-one")
    assert not limiter.is_blocked("client-one")
    limiter.record_session_failure("client-one")
    assert limiter.is_blocked("client-one") and not limiter.is_blocked("loopback-tools")
    now[0] = 299.999
    assert limiter.is_blocked("client-one")
    now[0] = 300
    assert not limiter.is_blocked("client-one")
    for _ in range(4):
        limiter.record_session_failure("client-two")
    now[0] += 60
    limiter.record_session_failure("client-two")
    assert not limiter.is_blocked("client-two")


def test_pairing_bucket_is_separate_ten_attempts_two_minutes_and_success_preserves_block():
    now = [0]
    limiter = RateLimiter(clock=lambda: now[0])
    for _ in range(9):
        limiter.record_pairing_failure("client")
    for _ in range(4):
        limiter.record_session_failure("client")
    assert not limiter.is_blocked("client")
    limiter.record_pairing_failure("client")
    now[0] = 119.999
    assert limiter.is_blocked("client")
    now[0] = 120
    assert not limiter.is_blocked("client")
    for _ in range(4):
        limiter.record_session_failure("client")
    limiter.record_success("client")
    limiter.record_session_failure("client")
    assert not limiter.is_blocked("client")
    for _ in range(10):
        limiter.record_pairing_failure("other")
    limiter.record_success("other")
    assert limiter.is_blocked("other")
    now[0] += 120
    assert not limiter.is_blocked("other")
