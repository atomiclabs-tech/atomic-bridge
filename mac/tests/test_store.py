import json
import logging
import os
from pathlib import Path

import pytest

from atomic_relay.sessions.store import SessionStore
from session_cases import FIXTURE


def test_sync_failure_is_logged_and_never_replaces_credentials(tmp_path, monkeypatch, caplog):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    path = tmp_path / "sessions.json"
    path.write_bytes(FIXTURE.read_bytes())
    store = SessionStore(path)
    state = store.load()
    def denied(fd):
        raise OSError("synthetic fsync failure")
    monkeypatch.setattr(os, "fsync", denied)
    with caplog.at_level(logging.ERROR), pytest.raises(OSError, match="fsync failure"):
        store.save(*state)
    assert path.read_bytes() == FIXTURE.read_bytes() and not list(tmp_path.glob("*.tmp"))
    assert caplog.records[-1].exc_info


def test_unreadable_file_is_fatal_with_stack_trace(tmp_path, monkeypatch, caplog):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    path = tmp_path / "sessions.json"
    path.write_bytes(FIXTURE.read_bytes())
    def denied(*args, **kwargs):
        raise PermissionError("synthetic sessions read failure")
    monkeypatch.setattr(Path, "read_text", denied)
    with caplog.at_level(logging.ERROR), pytest.raises(PermissionError):
        SessionStore(path).load()
    assert caplog.records[-1].exc_info and "operation=load" in caplog.text


def test_optional_supervised_metadata_and_trusted_grants_round_trip(tmp_path):
    path = tmp_path / "sessions.json"
    data = json.loads(FIXTURE.read_text())
    supervised = {"active": True, "profile_label": "Synthetic profile", "capabilities": ["text_chat"],
                  "enforcement_owner": "android_client"}
    data["sessions"][0]["supervised_mode"] = supervised
    data["trusted_devices"][0].update(supervised_mode=supervised, grants={"bridge": 0.0})
    path.write_text(json.dumps(data))
    store = SessionStore(path)
    state = store.load()
    store.save(*state)
    assert json.loads(path.read_text()) == data
