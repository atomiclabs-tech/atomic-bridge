import asyncio
import hashlib

import pytest

from atomic_relay.media.registry import MediaRegistry
from media_cases import path_policy


def test_ttl_lru_hash_and_owned_files_only(tmp_path, monkeypatch):
    now = [0]
    policy = path_policy(tmp_path, monkeypatch)
    registry = MediaRegistry(policy, 10, max_entries=2, clock=lambda: now[0])
    entries = []
    for index in range(2):
        path = policy.media_dir / f"atomic-upload-{index}"
        path.write_bytes(b"synthetic")
        entries.append(registry.register(path, "text/plain", owned_file=True, upload=True))
    assert entries[0].size == 9 and entries[0].sha256 == hashlib.sha256(b"synthetic").hexdigest()
    assert registry.get(entries[0].token) is entries[0]
    caller = tmp_path / "allowed" / "caller-file"
    caller.write_bytes(b"caller")
    third = registry.register(caller, "text/plain")
    assert not entries[1].path.exists() and entries[0].path.exists()
    assert registry.get(entries[1].token) is None
    assert registry.mark_sensitive(third.token) and third.sensitive
    now[0] = 11
    assert registry.cleanup() == 2
    assert not entries[0].path.exists() and caller.exists()
    assert registry.get(third.token) is None and not registry.mark_sensitive(third.token)


def test_shared_owned_file_retires_only_after_all_references_expire(tmp_path, monkeypatch):
    now = [0]
    policy = path_policy(tmp_path, monkeypatch)
    registry = MediaRegistry(policy, 5, clock=lambda: now[0])
    path = policy.media_dir / "atomic-upload-shared"
    path.write_bytes(b"shared")
    registry.register(path, "text/plain", owned_file=True, upload=True)
    now[0] = 1
    registry.register(path, "text/plain", owned_file=True, upload=True)
    now[0] = 5.5
    assert registry.cleanup() == 1 and path.exists()
    now[0] = 7
    assert registry.cleanup() == 1 and not path.exists()


def test_cleanup_failure_is_not_hidden_and_maintenance_uses_upstream_interval(tmp_path, monkeypatch):
    now = [0]
    policy = path_policy(tmp_path, monkeypatch)
    registry = MediaRegistry(policy, 1, clock=lambda: now[0])
    path = policy.media_dir / "atomic-upload-cleanup"
    path.write_bytes(b"x")
    registry.register(path, "text/plain", owned_file=True, upload=True)
    original = type(path).unlink
    def denied(self, *args, **kwargs):
        raise PermissionError("synthetic cleanup failure")
    monkeypatch.setattr(type(path), "unlink", denied)
    now[0] = 2
    with pytest.raises(PermissionError):
        registry.cleanup()
    monkeypatch.setattr(type(path), "unlink", original)
    intervals = []
    async def sleep(delay):
        intervals.append(delay)
        if len(intervals) == 2:
            raise asyncio.CancelledError
    monkeypatch.setattr("atomic_relay.media.registry.asyncio.sleep", sleep)
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(registry.maintain())
    assert intervals == [300, 300] and not path.exists()
