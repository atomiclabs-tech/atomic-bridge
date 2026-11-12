import logging
import os

import pytest

from atomic_relay.media.paths import MediaError
from atomic_relay.media.registry import MediaRegistry
from atomic_relay.media import registry as registry_module
from media_cases import path_policy


def test_registration_refuses_parent_swap_after_root_validation(tmp_path, monkeypatch):
    policy = path_policy(tmp_path, monkeypatch)
    registry = MediaRegistry(policy, 10)
    parent = tmp_path / "allowed" / "parent"
    parent.mkdir()
    source = parent / "source"
    source.write_bytes(b"synthetic")
    inspect = registry_module.inspect_file
    def swapped(path, maximum):
        moved = tmp_path / "outside" / "moved"
        parent.rename(moved)
        parent.symlink_to(moved, target_is_directory=True)
        return inspect(path, maximum)
    monkeypatch.setattr(registry_module, "inspect_file", swapped)
    with pytest.raises(MediaError) as error:
        registry.register(source, "text/plain")
    assert error.value.status == 409 and str(error.value) == "registered media changed"
    assert not registry.entries


def test_ownership_replacement_is_retained_and_unmanaged_ownership_is_rejected(tmp_path, monkeypatch, caplog):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    policy = path_policy(tmp_path, monkeypatch)
    registry = MediaRegistry(policy, 10)
    assert registry.max_entries == 500
    source = tmp_path / "allowed" / "caller"
    source.write_bytes(b"source")
    with pytest.raises(MediaError):
        registry.register(source, "text/plain", owned_file=True)
    path = policy.media_dir / "atomic-upload-owned"
    path.write_bytes(b"old")
    entry = registry.register(path, "text/plain", owned_file=True, upload=True)
    replacement = policy.media_dir / "replacement"
    replacement.write_bytes(b"new")
    os.replace(replacement, path)
    with pytest.raises(MediaError):
        registry.open(entry)
    with caplog.at_level(logging.WARNING):
        registry.close()
    assert path.read_bytes() == b"new" and "ownership changed" in caplog.text
    assert str(path) not in caplog.text


def test_same_size_content_change_is_refused_before_any_download_bytes(tmp_path, monkeypatch):
    policy = path_policy(tmp_path, monkeypatch)
    registry = MediaRegistry(policy, 10)
    source = tmp_path / "allowed" / "changing"
    source.write_bytes(b"original")
    entry = registry.register(source, "text/plain")
    source.write_bytes(b"modified")
    with pytest.raises(MediaError) as error:
        registry.open(entry)
    assert error.value.status == 409
