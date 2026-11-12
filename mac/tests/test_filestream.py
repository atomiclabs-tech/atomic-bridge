import hashlib
import io
import logging
from pathlib import Path

import pytest

from atomic_bridge import filestream
from atomic_bridge.client import RelayResponse
from atomic_bridge.filestream import MAX_FILE_BYTES, FileTransferError, inspect_upload, pull_to_path
from new_tool_cases import DIGEST, PAYLOAD, TOKEN, metadata


class Download:
    def __init__(self, *, value=None, status=200, fail=None):
        self.value = {"size": len(PAYLOAD), "sha256": DIGEST, "content_type": "application/octet-stream"} if value is None else value
        self.status, self.fail = status, fail
        self.calls = []

    def download(self, path, sink, *, max_bytes, timeout):
        self.calls.append((path, max_bytes, timeout))
        self.sink = sink
        for start in range(0, len(PAYLOAD), 65536):
            sink.write(PAYLOAD[start:start + 65536])
            if self.fail:
                raise self.fail
        return RelayResponse(self.status, self.value)


@pytest.mark.parametrize("directory", [False, True])
def test_pull_writes_in_actual_destination_directory_and_atomically_replaces(tmp_path, monkeypatch, directory):
    destination = tmp_path / "destination"
    destination.mkdir()
    target = destination / "synthetic.bin"
    target.write_bytes(b"existing good file")
    client = Download()
    replace = filestream.os.replace
    observations = []

    def observed(source, dest):
        observations.append(Path(source).parent == target.parent and Path(dest) == target and target.read_bytes() == b"existing good file")
        replace(source, dest)

    monkeypatch.setattr(filestream.os, "replace", observed)
    result = pull_to_path(client, metadata(), str(destination if directory else target))
    assert observations == [True]
    assert target.read_bytes() == PAYLOAD
    assert result == {"path": str(target), "size": len(PAYLOAD), "sha256": DIGEST, "content_type": "application/octet-stream"}
    assert client.calls == [("/media/" + TOKEN, 256 * 1024 * 1024, 300)]
    assert client.sink.closed and list(destination.iterdir()) == [target]


def test_pull_uses_configured_default_and_declared_fallback(tmp_path, monkeypatch):
    configured = tmp_path / "configured"
    result = pull_to_path(Download(), metadata(), environ={"ATOMIC_BRIDGE_PULL_DIR": str(configured)})
    assert Path(result["path"]).parent == configured
    original = Path.expanduser

    def expand(path):
        return tmp_path / "fallback" if str(path) == "~/.hermes/workspace/phone-files" else original(path)

    monkeypatch.setattr(Path, "expanduser", expand)
    result = pull_to_path(Download(), metadata(), environ={})
    assert Path(result["path"]).parent == tmp_path / "fallback"


@pytest.mark.parametrize("changes,cause", [({"size": len(PAYLOAD) + 1}, "size"), ({"sha256": "0" * 64}, "SHA-256")])
def test_mismatches_leave_no_temporary_file_and_preserve_existing_target(tmp_path, changes, cause):
    target = tmp_path / "synthetic.bin"
    target.write_bytes(b"existing good file")
    with pytest.raises(FileTransferError) as error:
        pull_to_path(Download(), metadata(**changes), str(target))
    assert cause in error.value.value["error"] and error.value.value["stage"] == "verify"
    assert target.read_bytes() == b"existing good file" and list(tmp_path.iterdir()) == [target]


@pytest.mark.parametrize("changes", [
    {"name": "../escape"}, {"name": "nested/name"}, {"name": "nested\\name"},
    {"name": ".."}, {"name": ""}, {"media": "MEDIA:hermes-relay://../escape"},
    {"size": MAX_FILE_BYTES + 1}, {"size": True}, {"sha256": "invalid"},
])
def test_invalid_phone_metadata_fails_before_file_or_download(tmp_path, changes):
    client = Download()
    with pytest.raises(FileTransferError):
        pull_to_path(client, metadata(**changes), str(tmp_path))
    assert client.calls == [] and list(tmp_path.iterdir()) == []


@pytest.mark.parametrize("client", [Download(status=410, value={"error": "HTTP 410: synthetic expired"}), Download(fail=OSError("synthetic failure"))])
def test_fetch_failure_cleans_partial_files_without_retry(tmp_path, client):
    with pytest.raises(FileTransferError) as error:
        pull_to_path(client, metadata(), str(tmp_path))
    assert error.value.value["stage"] == "fetch"
    assert client.sink.closed and len(client.calls) == 1 and list(tmp_path.iterdir()) == []


def test_commit_failure_preserves_old_file_and_cleans_temp(tmp_path, monkeypatch):
    target = tmp_path / "synthetic.bin"
    target.write_bytes(b"existing good file")

    def fail(*args):
        raise PermissionError("synthetic rename refusal")

    monkeypatch.setattr(filestream.os, "replace", fail)
    with pytest.raises(FileTransferError) as error:
        pull_to_path(Download(), metadata(), str(target))
    assert error.value.value == {"error": "File download failed: PermissionError", "stage": "commit"}
    assert isinstance(error.value.__cause__, PermissionError)
    assert target.read_bytes() == b"existing good file" and list(tmp_path.iterdir()) == [target]


def test_empty_configuration_is_an_error(tmp_path):
    with pytest.raises(FileTransferError) as error:
        pull_to_path(Download(), metadata(), environ={"ATOMIC_BRIDGE_PULL_DIR": ""})
    assert "ATOMIC_BRIDGE_PULL_DIR" in error.value.value["error"]


def test_upload_hashes_bounded_chunks(tmp_path, monkeypatch):
    source = tmp_path / "synthetic.bin"
    source.write_bytes(PAYLOAD)
    opened = Path.open
    budgets = []

    class Reader:
        def __init__(self, stream):
            self.stream = stream

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return self.stream.__exit__(*args)

        def read(self, size):
            budgets.append(size)
            return self.stream.read(size)

        def fileno(self):
            return self.stream.fileno()

    monkeypatch.setattr(Path, "open", lambda path, *args, **kwargs: Reader(opened(path, *args, **kwargs)))
    result = inspect_upload(str(source))
    assert (result.size, result.sha256) == (len(PAYLOAD), hashlib.sha256(PAYLOAD).hexdigest())
    assert len(budgets) >= 3 and all(0 < size <= 65536 for size in budgets)


def test_file_changing_during_hash_is_rejected(tmp_path, monkeypatch):
    source = tmp_path / "synthetic.bin"
    source.write_bytes(PAYLOAD)
    opened = Path.open

    class Growing(io.BufferedReader):
        changed = False

        def read(self, size):
            chunk = super().read(size)
            if not self.changed:
                self.changed = True
                with opened(source, "ab") as writer:
                    writer.write(b"x")
            return chunk

    monkeypatch.setattr(Path, "open", lambda path, *args, **kwargs: Growing(opened(path, *args, **kwargs)))
    with pytest.raises(FileTransferError) as error:
        inspect_upload(str(source))
    assert "changed" in error.value.value["error"]


def test_upload_size_limit_and_regular_file_checks_happen_before_read(tmp_path, monkeypatch):
    source = tmp_path / "oversized.bin"
    with source.open("wb") as stream:
        stream.truncate(MAX_FILE_BYTES + 1)
    monkeypatch.setattr(Path, "open", lambda *args, **kwargs: pytest.fail("Rejected source was opened"))
    for path in (source, tmp_path):
        with pytest.raises(FileTransferError):
            inspect_upload(str(path))


def test_unknown_mime_fallback_warns(tmp_path, monkeypatch, caplog):
    source = tmp_path / "synthetic"
    source.write_bytes(b"synthetic")
    monkeypatch.setattr(filestream.mimetypes, "guess_type", lambda path: (None, None))
    with caplog.at_level(logging.WARNING, logger="atomic_bridge"):
        result = inspect_upload(str(source))
    assert result.content_type == "application/octet-stream" and "unknown MIME" in caplog.text
