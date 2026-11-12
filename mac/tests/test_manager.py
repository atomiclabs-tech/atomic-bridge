import json

import pytest

from atomic_relay.sessions.manager import SessionManager, refresh_hash
from atomic_relay.sessions.grants import DEFAULT_TTL_SECONDS, REFRESH_TTL_SECONDS


def test_refresh_slides_trusted_expiry_without_session_token_reconnect_sliding(tmp_path):
    now = [1000]
    path = tmp_path / "sessions.json"
    manager = SessionManager(path, clock=lambda: now[0], monotonic=lambda: now[0])
    original = manager.create_session("phone", "device", issue_refresh_token=True)
    initial_expiry = json.loads(path.read_text())["trusted_devices"][0]["expires_at"]
    now[0] += 86400
    assert manager.get_session(original.token) is original
    manager.flush(force=True)
    assert json.loads(path.read_text())["trusted_devices"][0]["expires_at"] == initial_expiry
    assert manager.refresh_session(original.refresh_token, device_name="phone", device_id="wrong") is None
    assert json.loads(path.read_text())["trusted_devices"][0]["expires_at"] == initial_expiry
    refreshed = manager.refresh_session(original.refresh_token, device_name="phone", device_id="device")
    assert refreshed is not None
    assert refreshed.expires_at == now[0] + DEFAULT_TTL_SECONDS
    assert json.loads(path.read_text())["trusted_devices"][0]["expires_at"] == now[0] + REFRESH_TTL_SECONDS
    assert all(expiry == refreshed.expires_at for expiry in refreshed.grants.values())
    now[0] = initial_expiry + 1
    reloaded = SessionManager(path, clock=lambda: now[0])
    second = reloaded.refresh_session(refreshed.refresh_token, device_name="phone", device_id="device")
    assert second is not None
    assert json.loads(path.read_text())["trusted_devices"][0]["expires_at"] == now[0] + REFRESH_TTL_SECONDS


def test_failed_refresh_preserves_original_credential_and_retry_can_recover(tmp_path, monkeypatch):
    path = tmp_path / "sessions.json"
    now = [1000]
    manager = SessionManager(path, clock=lambda: now[0])
    session = manager.create_session("phone", "device", ttl_seconds=0, issue_refresh_token=True)
    before = path.read_bytes()
    now[0] += 86400
    original = manager.store.save
    def denied(*args):
        raise OSError("synthetic failed refresh persistence")
    monkeypatch.setattr(manager.store, "save", denied)
    with pytest.raises(OSError, match="failed refresh persistence"):
        manager.refresh_session(session.refresh_token, device_name="phone", device_id="device")
    assert path.read_bytes() == before and manager.active_count() == 1
    monkeypatch.setattr(manager.store, "save", original)
    recovered = manager.refresh_session(session.refresh_token, device_name="phone", device_id="device")
    assert recovered is not None and manager.active_count() == 2
    hashes = [row["refresh_token_hash"] for row in json.loads(path.read_text())["trusted_devices"]]
    assert hashes == [refresh_hash(recovered.refresh_token)]
    assert json.loads(path.read_text())["trusted_devices"][0]["expires_at"] == now[0] + REFRESH_TTL_SECONDS


def test_missing_refresh_and_expired_trust_do_not_create_or_revive_session(tmp_path):
    now = [1000]
    manager = SessionManager(tmp_path / "sessions.json", clock=lambda: now[0])
    session = manager.create_session("phone", "device", ttl_seconds=1, issue_refresh_token=True)
    now[0] += 180 * 86400 + 1
    assert manager.refresh_session(session.refresh_token, device_name="phone", device_id="device") is None
    assert not manager.has_trusted_device("device") and manager.active_count() == 0


def test_reconnect_metadata_survives_refresh_without_unknown_values_erasing_it(tmp_path):
    manager = SessionManager(tmp_path / "sessions.json", clock=lambda: 1000)
    session = manager.create_session("phone", "device", ttl_seconds=0, issue_refresh_token=True)
    manager.update_session_device_metadata(session, device_model=" new model ", device_platform="Android synthetic")
    manager.update_session_device_metadata(session, device_model="unknown", device_platform="")
    refreshed = manager.refresh_session(session.refresh_token, device_name="phone", device_id="device")
    assert refreshed.device_model == "new model" and refreshed.device_platform == "Android synthetic"
