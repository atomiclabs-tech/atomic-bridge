import logging
import secrets

import pytest

from atomic_relay.sessions.pairing import PairingManager, normalize_code


def test_codes_full_alphabet_ten_minutes_single_use_and_no_logs(caplog, monkeypatch):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    now = [0]
    pairing = PairingManager(clock=lambda: now[0])
    synthetic = "IO01AZ"
    with caplog.at_level(logging.DEBUG):
        assert pairing.register_code(synthetic.lower(), ttl_seconds=0, grants={"bridge": 0}, transport_hint="wss")
        now[0] = 600
        assert pairing.validate_code(synthetic)
        metadata = pairing.consume_code(synthetic)
        assert metadata.ttl_seconds == 0 and metadata.grants == {"bridge": 0}
        assert metadata.transport_hint == "wss"
        assert pairing.consume_code(synthetic) is None
        assert pairing.register_code(synthetic)
        now[0] = 1200.001
        assert not pairing.validate_code(synthetic)
    assert synthetic not in caplog.text and synthetic.lower() not in caplog.text


@pytest.mark.parametrize("code", [None, "", "ABCDE", "ABCDEFG", "ABC-12", "日本語123"])
def test_bad_code_has_safe_error(code):
    with pytest.raises(ValueError, match="six A-Z"):
        normalize_code(code)
    assert not PairingManager().register_code(code)


@pytest.mark.parametrize("policy", [{"ttl_seconds": -1}, {"ttl_seconds": True}, {"ttl_seconds": 0.5},
                                   {"grants": []}, {"grants": {"bridge": -1}}, {"transport_hint": "invalid"}])
def test_invalid_policy_is_rejected(policy):
    with pytest.raises(ValueError):
        PairingManager().register_code("ABC123", **policy)


def test_abuse_cap_invalidates_every_pending_code_resets_and_allows_registration(caplog):
    pairing = PairingManager(clock=lambda: 0)
    codes = [secrets.token_hex(3).upper() for _ in range(3)]
    assert pairing.register_code(codes[0]) and pairing.register_code(codes[1])
    for _ in range(19):
        assert pairing.consume_code("ZZZZZZ") is None
    assert all(pairing.validate_code(code) for code in codes[:2])
    assert pairing.consume_code("ZZZZZZ") is None
    assert not any(pairing.validate_code(code) for code in codes[:2])
    assert pairing.register_code(codes[2])
    for _ in range(19):
        assert pairing.consume_code("ZZZZZZ") is None
    assert pairing.consume_code(codes[2]) is not None
    assert caplog.messages == ["component=pairing operation=abuse-cap cleared pending codes after 20 failures"]
    assert all(code not in caplog.text for code in codes)


def test_failure_window_is_sliding_and_validation_does_not_count(caplog):
    now = [0]
    pairing = PairingManager(clock=lambda: now[0])
    code = secrets.token_hex(3).upper()
    assert pairing.register_code(code)
    for _ in range(19):
        assert pairing.consume_code("ZZZZZZ") is None
        assert not pairing.validate_code("ZZZZZZ")
    now[0] = 600
    assert pairing.consume_code("ZZZZZZ") is None
    assert pairing.consume_code(code) is not None
    assert not caplog.records


def test_expired_and_malformed_consumption_count_toward_global_cap(caplog):
    now = [0]
    pairing = PairingManager(clock=lambda: now[0])
    expired, pending = secrets.token_hex(3).upper(), secrets.token_hex(3).upper()
    assert pairing.register_code(expired)
    now[0] = 601
    assert pairing.register_code(pending)
    assert pairing.consume_code(expired) is None
    for _ in range(18):
        assert pairing.consume_code(None) is None
    assert pairing.validate_code(pending)
    assert pairing.consume_code("") is None
    assert not pairing.validate_code(pending)
    assert caplog.messages == ["component=pairing operation=abuse-cap cleared pending codes after 20 failures"]
