"""Private credentials are installed once, never replaced on retry."""
# no_parallel: private credential fixtures use temporary home files

import os

import pytest

from atomic_relay.ops.secret import create_if_missing, read_secret


def test_create_once_private_complete_and_no_second_generation(tmp_path):
    path, calls = tmp_path / "home/admin.secret", []
    def generate():
        calls.append(True)
        return "synthetic-private-credential-value-" * 2
    assert create_if_missing(path, generate=generate)
    original, inode = path.read_bytes(), path.stat().st_ino
    assert read_secret(path) == original.decode().strip()
    assert path.stat().st_mode & 0o777 == 0o600
    assert not create_if_missing(path, generate=generate)
    assert len(calls) == 1 and path.stat().st_ino == inode and path.read_bytes() == original


@pytest.mark.parametrize("data,mode", [(b"short", 0o600), (b"x" * 40, 0o644),
                                      (b"x" * 40 + b"\nsecond", 0o600), (b"\xff" * 40, 0o600)])
def test_existing_invalid_secret_is_never_replaced_or_disclosed(tmp_path, data, mode):
    path = tmp_path / "admin.secret"
    path.write_bytes(data)
    path.chmod(mode)
    with pytest.raises(ValueError) as result:
        create_if_missing(path)
    assert "xxxx" not in str(result.value) and path.read_bytes() == data


def test_no_symlink_following_or_partial_secret_on_publish_failure(tmp_path, monkeypatch):
    path, target = tmp_path / "admin.secret", tmp_path / "target"
    target.write_bytes(b"x" * 40)
    target.chmod(0o600)
    path.symlink_to(target)
    with pytest.raises(ValueError):
        read_secret(path)
    path.unlink()
    def fail(*args):
        raise OSError("synthetic publish failure")
    monkeypatch.setattr(os, "link", fail)
    with pytest.raises(OSError, match="synthetic"):
        create_if_missing(path)
    assert not path.exists() and list(tmp_path.iterdir()) == [target]


def test_concurrent_valid_creator_is_retained(tmp_path, monkeypatch):
    path = tmp_path / "admin.secret"
    def race(source, target):
        target.write_bytes(b"synthetic-concurrent-winner-" * 2)
        target.chmod(0o600)
        raise FileExistsError("concurrent creator")
    monkeypatch.setattr(os, "link", race)
    assert not create_if_missing(path)
    assert path.read_bytes() == b"synthetic-concurrent-winner-" * 2
    assert list(tmp_path.iterdir()) == [path]
