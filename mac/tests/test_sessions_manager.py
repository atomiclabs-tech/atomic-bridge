import json
import logging

import pytest

from atomic_relay.sessions.manager import SessionManager, refresh_hash
from session_cases import FIXTURE


def test_create_extend_revoke_persist_and_repair_same_device(tmp_path):
    path = tmp_path / "sessions.json"
    manager = SessionManager(path, clock=lambda: 1000)
    old = manager.create_session("phone", "device", ttl_seconds=0, issue_refresh_token=True)
    assert len(json.loads(path.read_text())["trusted_devices"]) == 1
    new = manager.create_session("phone", "device", ttl_seconds=100, issue_refresh_token=True)
    assert manager.get_session(old.token) is None
    assert not manager.refresh_session(old.refresh_token, device_name="phone", device_id="device")
    manager.update_session(new.token, ttl_seconds=200, grants={"bridge": 150})
    state = json.loads(path.read_text())
    assert state["sessions"][0]["expires_at"] == 1200
    assert state["sessions"][0]["grants"]["bridge"] == 1150
    assert manager.revoke_session(new.token)
    assert json.loads(path.read_text()) == {"version": 1, "sessions": [], "trusted_devices": []}
    assert not manager.revoke_session(new.token)


def test_refresh_rotates_hash_rejects_mismatch_and_keeps_old_session(tmp_path):
    path = tmp_path / "sessions.json"
    manager = SessionManager(path, clock=lambda: 1000)
    old = manager.create_session("phone", "device", ttl_seconds=0, issue_refresh_token=True)
    assert manager.refresh_session(old.refresh_token, device_name="other", device_id="wrong") is None
    refreshed = manager.refresh_session(old.refresh_token, device_name="phone", device_id="device")
    assert refreshed and manager.get_session(old.token) is old
    assert refreshed.refresh_token != old.refresh_token
    hashes = [row["refresh_token_hash"] for row in json.loads(path.read_text())["trusted_devices"]]
    assert refresh_hash(old.refresh_token) not in hashes
    assert refresh_hash(refreshed.refresh_token) in hashes
    assert manager.refresh_session(old.refresh_token, device_name="phone", device_id="device") is None
    assert len(SessionManager(path, clock=lambda: 1000).list_sessions()) == 2


def test_last_seen_is_throttled_and_shutdown_flushes_dirty_state(tmp_path):
    path = tmp_path / "sessions.json"
    now = [1000]
    manager = SessionManager(path, clock=lambda: now[0], monotonic=lambda: now[0])
    session = manager.create_session("phone", "device", ttl_seconds=0)
    initial = path.read_bytes()
    for stamp in (1001, 1030, 1059):
        now[0] = stamp
        assert manager.get_session(session.token)
        assert path.read_bytes() == initial
    now[0] = 1060
    manager.get_session(session.token)
    assert json.loads(path.read_text())["sessions"][0]["last_seen"] == 1060
    now[0] = 1061
    manager.get_session(session.token)
    manager.flush(force=True)
    assert json.loads(path.read_text())["sessions"][0]["last_seen"] == 1061


def test_load_and_clean_shutdown_do_not_rewrite(tmp_path):
    path = tmp_path / "sessions.json"
    path.write_bytes(FIXTURE.read_bytes())
    before = path.stat().st_mtime_ns
    manager = SessionManager(path)
    assert manager.has_trusted_device("synthetic-device")
    manager.flush(force=True)
    assert path.read_bytes() == FIXTURE.read_bytes() and path.stat().st_mtime_ns == before


def test_failed_persistence_rolls_back_creation_and_reduction(tmp_path, monkeypatch):
    manager = SessionManager(tmp_path / "sessions.json", clock=lambda: 1000)
    session = manager.create_session("phone", "device", ttl_seconds=100)
    def denied(*args):
        raise OSError("synthetic write failure")
    monkeypatch.setattr(manager.store, "save", denied)
    with pytest.raises(OSError):
        manager.create_session("other", "other")
    assert manager.active_count() == 1
    with pytest.raises(OSError):
        manager.reduce_session_policy(session.token, ttl_seconds=10)
    assert manager.list_sessions()[0].expires_at == 1100


def test_adopt_refresh_metadata_expiry_and_reduce(tmp_path):
    now = [1000]
    manager = SessionManager(tmp_path / "sessions.json", clock=lambda: now[0])
    session = manager.create_session("phone", "device", ttl_seconds=100)
    assert manager.issue_refresh_token_for_session(session)
    manager.update_session_device_metadata(session, device_model="new model")
    assert manager.has_trusted_device("device")
    assert manager.reduce_session_policy(session.token, ttl_seconds=50).expires_at == 1050
    with pytest.raises(PermissionError):
        manager.reduce_session_policy(session.token, ttl_seconds=0)
    now[0] = 1051
    assert manager.get_session(session.token) is None
    assert manager.update_session(session.token, ttl_seconds=100) is None


@pytest.mark.parametrize("existing", ["session", "trusted"])
def test_pairing_eviction_warns_only_device_id_before_replacement(tmp_path, caplog, existing):
    now = [1000]
    manager = SessionManager(tmp_path / "sessions.json", clock=lambda: now[0])
    old = manager.create_session("synthetic-private-name", "repaired-device", ttl_seconds=1 if existing == "trusted" else 0,
                                 issue_refresh_token=existing == "trusted")
    other = manager.create_session("other", "unrelated", ttl_seconds=0)
    if existing == "trusted":
        now[0] += 2
        assert manager.get_session(old.token) is None and manager.has_trusted_device("repaired-device")
    with caplog.at_level(logging.WARNING):
        replacement = manager.create_session("synthetic-private-replacement", "repaired-device", ttl_seconds=0, issue_refresh_token=True)
    assert caplog.messages == ["component=sessions operation=pair replaced existing trusted device device_id=repaired-device"]
    assert manager.get_session(old.token) is None and manager.get_session(other.token) is other
    assert manager.get_session(replacement.token) is replacement
    private = (old.token, old.refresh_token, replacement.token, replacement.refresh_token,
               "synthetic-private-name", "synthetic-private-replacement")
    assert all(value not in caplog.text for value in private if value)


def test_session_creation_and_refresh_rotation_do_not_warn_about_pairing(tmp_path, caplog):
    manager = SessionManager(tmp_path / "sessions.json", clock=lambda: 1000)
    original = manager.create_session("phone", "device", ttl_seconds=0, issue_refresh_token=True)
    manager.create_session("phone", "device", ttl_seconds=0)
    assert manager.refresh_session(original.refresh_token, device_name="phone", device_id="device") is not None
    assert not caplog.records
