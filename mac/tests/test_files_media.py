import hashlib
import io
import os
import stat

import pytest

from atomic_relay.media.files import CHUNK_SIZE, UploadFile, image_type, inspect_file, open_nofollow, open_verified
from atomic_relay.media import files
from atomic_relay.media.paths import MediaError


@pytest.mark.parametrize("failure", [None, "parent", "leaf", "directory", "fifo", "open", "fstat", "fdopen"])
def test_nofollow_walk_flags_and_descriptor_cleanup_on_success_and_failure(tmp_path, monkeypatch, failure):
    parent = tmp_path / "parent"
    parent.mkdir()
    source = parent / "source"
    source.write_bytes(b"synthetic")
    if failure in {"parent", "leaf"}:
        target = parent if failure == "parent" else source
        moved = target.with_name("moved")
        target.rename(moved)
        target.symlink_to(moved, target_is_directory=failure == "parent")
    elif failure in {"directory", "fifo"}:
        source.unlink()
        source.mkdir() if failure == "directory" else os.mkfifo(source)
    opened = []
    original_open, original_fstat, original_fdopen = os.open, os.fstat, os.fdopen
    def tracked(name, flags, *, dir_fd=None):
        if failure == "open" and name == "source":
            raise PermissionError("synthetic open refusal")
        descriptor = original_open(name, flags, dir_fd=dir_fd)
        opened.append((descriptor, flags, dir_fd))
        return descriptor
    def checked(descriptor):
        if failure == "fstat" and descriptor == opened[-1][0]:
            raise OSError("synthetic fstat refusal")
        return original_fstat(descriptor)
    def wrapped(descriptor, mode):
        if failure == "fdopen":
            raise OSError("synthetic fdopen refusal")
        return original_fdopen(descriptor, mode)
    monkeypatch.setattr(files.os, "open", tracked)
    monkeypatch.setattr(files.os, "fstat", checked)
    monkeypatch.setattr(files.os, "fdopen", wrapped)
    if failure is None:
        with open_nofollow(source) as stream:
            assert stream.read() == b"synthetic"
            for descriptor, _, _ in opened[:-1]:
                with pytest.raises(OSError):
                    original_fstat(descriptor)
    else:
        with pytest.raises(MediaError) as error:
            open_nofollow(source)
        assert error.value.status == 409 and str(error.value) == "registered media changed"
    assert opened and all(flags & os.O_NOFOLLOW for _, flags, _ in opened)
    assert opened[0][1] & os.O_DIRECTORY and opened[0][2] is None
    assert all(dir_fd is not None for _, _, dir_fd in opened[1:])
    if failure not in {"parent", "leaf", "open"}:
        assert opened[-1][1] & os.O_NONBLOCK
        assert all(flags & os.O_DIRECTORY for _, flags, _ in opened[:-1])
    for descriptor, _, _ in opened:
        with pytest.raises(OSError):
            original_fstat(descriptor)


def test_inspection_uses_pinned_parent_when_path_is_swapped_after_directory_open(tmp_path, monkeypatch):
    parent = tmp_path / "parent"
    parent.mkdir()
    source = parent / "source"
    source.write_bytes(b"original")
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "source").write_bytes(b"replaced")
    expected = source.stat()
    original_open = os.open
    def swapped(name, flags, *, dir_fd=None):
        descriptor = original_open(name, flags, dir_fd=dir_fd)
        if name == "parent":
            parent.rename(tmp_path / "moved")
            parent.symlink_to(outside, target_is_directory=True)
        return descriptor
    monkeypatch.setattr(files.os, "open", swapped)
    size, digest, file_id = inspect_file(source, 1024)
    assert size == 8 and digest == hashlib.sha256(b"original").hexdigest()
    assert file_id == (expected.st_dev, expected.st_ino)


def test_verified_open_preserves_regular_bytes_and_rejects_identity_replacement(tmp_path):
    source = tmp_path / "source"
    source.write_bytes(b"synthetic")
    size, digest, file_id = inspect_file(source, 1024)
    with open_verified(source, size, file_id, digest) as stream:
        assert stream.read() == b"synthetic"
    replacement = tmp_path / "replacement"
    replacement.write_bytes(b"synthetic")
    replacement.replace(source)
    with pytest.raises(MediaError) as error:
        open_verified(source, size, file_id, digest)
    assert error.value.status == 409


def test_upload_writes_hashes_checks_chunk_cap_and_removes_aborts(tmp_path):
    with UploadFile(tmp_path, CHUNK_SIZE * 2) as output:
        assert stat.S_IMODE(output.path.stat().st_mode) == 0o600
        output.write(b"x" * CHUNK_SIZE)
        output.write(b"y" * CHUNK_SIZE)
        output.finish()
        size, digest, _ = inspect_file(output.path, CHUNK_SIZE * 2)
        assert size == output.size == CHUNK_SIZE * 2 and digest == output.digest.hexdigest()
        assert digest == hashlib.sha256(b"x" * CHUNK_SIZE + b"y" * CHUNK_SIZE).hexdigest()
    assert not output.path.exists()
    for data, maximum in ((b"x" * (CHUNK_SIZE + 1), CHUNK_SIZE * 2), (b"large", 1)):
        with pytest.raises(MediaError):
            with UploadFile(tmp_path, maximum) as output:
                output.write(data)
        assert not output.path.exists()


@pytest.mark.parametrize(("data", "mime"), [(b"\x89PNG\r\n\x1a\n", "image/png"), (b"\xff\xd8\xff", "image/jpeg"),
                                          (b"GIF89a", "image/gif"), (b"RIFF1234WEBP", "image/webp")])
def test_image_magic_is_checked_and_cursor_restored(data, mime):
    source = io.BytesIO(data)
    assert image_type(source, "image/incorrect") == mime and source.tell() == 0
    assert image_type(source, "application/octet-stream") == "application/octet-stream"
    with pytest.raises(MediaError) as error:
        image_type(io.BytesIO(b"not an image"), "image/png")
    assert error.value.status == 415
