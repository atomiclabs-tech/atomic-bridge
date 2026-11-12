import json
import logging
import os
import stat

import pytest

from atomic_relay.sessions.store import SessionStore
from atomic_relay.sessions.authentication import Authentication
from atomic_relay.sessions.manager import SessionManager
from atomic_relay.sessions.pairing import PairingManager
from atomic_relay.sessions.ratelimit import RateLimiter
from session_cases import FIXTURE


def test_never_fixture_authenticates_without_rewrite_and_flush_only_updates_last_seen(tmp_path):
    path = tmp_path / "sessions.json"
    original = FIXTURE.read_bytes()
    expected = json.loads(original)
    now = expected["sessions"][0]["last_seen"] + 1
    path.write_bytes(original)
    manager = SessionManager(path, clock=lambda: now, monotonic=lambda: 0)
    assert path.read_bytes() == original
    session = manager.list_sessions()[0]
    assert expected["sessions"][0]["expires_at"] == "never"
    assert all(expiry == "never" for expiry in expected["sessions"][0]["grants"].values())
    authentication = Authentication(manager, PairingManager(), RateLimiter())
    assert authentication.authenticate({"session_token": session.token, "device_id": session.device_id}, "synthetic-client") is session
    assert path.read_bytes() == original
    manager.flush(force=True)
    expected["sessions"][0]["last_seen"] = now
    unchanged_credentials = json.loads(path.read_bytes()) == expected
    assert unchanged_credentials
    reloaded = SessionManager(path, clock=lambda: now)
    assert reloaded.get_session(session.token) is not None


def test_fixture_round_trip_preserves_fields_types_and_load_is_read_only(tmp_path, monkeypatch):
    path = tmp_path / "sessions.json"
    original = FIXTURE.read_bytes()
    path.write_bytes(original)
    before = path.stat().st_mtime_ns
    store = SessionStore(path)
    sessions, devices = store.load()
    assert len(sessions) == len(devices) == 1
    assert sessions[0].device_model == devices[0].device_model == "Synthetic model"
    assert not sessions[0].is_expired and not devices[0].is_expired
    assert path.read_bytes() == original and path.stat().st_mtime_ns == before
    rename = os.replace
    observed = []
    def replace(source, target):
        assert source.parent == target.parent and source != target
        assert stat.S_IMODE(source.stat().st_mode) == 0o600
        observed.append(True)
        return rename(source, target)
    monkeypatch.setattr(os, "replace", replace)
    store.save(sessions, devices)
    expected, actual = json.loads(original), json.loads(path.read_bytes())
    assert expected == actual
    for group in ("sessions", "trusted_devices"):
        assert {key: type(value) for key, value in expected[group][0].items()} == {
            key: type(value) for key, value in actual[group][0].items()
        }
    assert observed == [True] and stat.S_IMODE(path.stat().st_mode) == 0o600
    assert not list(tmp_path.glob("*.tmp"))


@pytest.mark.parametrize("contents", ["{bad", "[]", '{"version":1,"sessions":[{}],"trusted_devices":[]}'])
def test_corrupt_store_fails_loudly_without_overwrite(tmp_path, caplog, monkeypatch, contents):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    path = tmp_path / "sessions.json"
    path.write_text(contents)
    with caplog.at_level(logging.ERROR), pytest.raises((ValueError, KeyError)):
        SessionStore(path).load()
    assert path.read_text() == contents
    assert caplog.records[-1].exc_info
    assert "operation=load" in caplog.text


def test_write_failure_retains_old_file_and_cleans_temp(tmp_path, monkeypatch, caplog):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    path = tmp_path / "sessions.json"
    path.write_bytes(FIXTURE.read_bytes())
    store = SessionStore(path)
    state = store.load()
    def denied(*args):
        raise OSError("synthetic rename failure")
    monkeypatch.setattr(os, "replace", denied)
    with caplog.at_level(logging.ERROR), pytest.raises(OSError):
        store.save(*state)
    assert path.read_bytes() == FIXTURE.read_bytes()
    assert not list(tmp_path.glob("*.tmp"))
    assert caplog.records[-1].exc_info and "operation=save" in caplog.text


def test_memory_and_missing_file_are_not_created(tmp_path):
    assert SessionStore(None).load() == ([], [])
    SessionStore(None).save([], [])
    path = tmp_path / "absent.json"
    assert SessionStore(path).load() == ([], [])
    assert not path.exists()
