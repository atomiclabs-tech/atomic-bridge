"""Selected environment edits preserve all unrelated operator bytes."""
# no_parallel: edits private operator files under a temporary home

import os

import pytest

from atomic_relay.ops.envfile import atomic_write, update


def test_four_upgrade_assignments_preserve_comments_unknown_quoting_and_are_idempotent(tmp_path):
    tmp_path = tmp_path / "folders" / "operator home"
    tmp_path.mkdir(parents=True)
    path = tmp_path / "relay.env"
    untouched = b'# retained comment\r\nUNKNOWN="quoted # value"\r\nUNRELATED=\'single value\'\r\n'
    path.write_bytes(untouched + b"ATOMIC_RELAY_HOST=old\r\nATOMIC_RELAY_HOST=duplicate\r\nATOMIC_RELAY_SERVER_VERSION=old")
    values = {"ATOMIC_RELAY_HOST": "127.0.0.1", "ATOMIC_RELAY_TRUSTED_PROXIES": "127.0.0.1",
              "ATOMIC_RELAY_ADMIN_SECRET_FILE": str(tmp_path / "operator home/admin.secret"),
              "ATOMIC_RELAY_SERVER_VERSION": "2.4.0"}
    assert update(path, values)
    data, inode = path.read_bytes(), path.stat().st_ino
    assert data.startswith(untouched)
    lines = data.splitlines()
    for obsolete in (b"ATOMIC_RELAY_HOST=old", b"ATOMIC_RELAY_HOST=duplicate", b"ATOMIC_RELAY_SERVER_VERSION=old"):
        assert obsolete not in lines
    assert lines.count(b"ATOMIC_RELAY_HOST=127.0.0.1") == 2
    assert b"ATOMIC_RELAY_TRUSTED_PROXIES=127.0.0.1" in lines
    assert b"ATOMIC_RELAY_SERVER_VERSION=2.4.0" in lines
    assert ("ATOMIC_RELAY_ADMIN_SECRET_FILE='" + values["ATOMIC_RELAY_ADMIN_SECRET_FILE"] + "'").encode() in lines
    assert path.stat().st_mode & 0o777 == 0o600
    assert not update(path, values) and path.stat().st_ino == inode and path.read_bytes() == data


def test_atomic_replace_failure_retains_original_and_cleans_staging(tmp_path, monkeypatch):
    path = tmp_path / "relay.env"
    path.write_bytes(b"unchanged")
    def fail(*args):
        raise OSError("synthetic replace failure")
    monkeypatch.setattr(os, "replace", fail)
    with pytest.raises(OSError, match="synthetic"):
        atomic_write(path, b"replacement")
    assert path.read_bytes() == b"unchanged" and list(tmp_path.iterdir()) == [path]


@pytest.mark.parametrize("values", [{"BAD KEY": "value"}, {"KEY": "line\nnew"}, {"KEY": "can't"}])
def test_invalid_assignment_is_refused_without_changes(tmp_path, values):
    path = tmp_path / "relay.env"
    path.write_bytes(b"# retained\n")
    with pytest.raises(ValueError):
        update(path, values)
    assert path.read_bytes() == b"# retained\n"


def test_symlink_is_refused(tmp_path):
    target, link = tmp_path / "target", tmp_path / "relay.env"
    target.write_bytes(b"retained")
    link.symlink_to(target)
    with pytest.raises(ValueError):
        update(link, {"KEY": "value"})
    assert target.read_bytes() == b"retained"
