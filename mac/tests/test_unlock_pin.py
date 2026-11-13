import os
import secrets
import string
import traceback
from contextlib import contextmanager
from types import SimpleNamespace

import pytest

from atomic_relay.sessions.unlock_pin import UnlockPin, UnlockPinError


def private_pin(path):
    pin = "".join(secrets.choice(string.digits) for _ in range(6))
    path.write_text(pin)
    path.chmod(0o600)
    return pin


def test_symlink_is_refused_without_reading_target(tmp_path):
    target, path = tmp_path / "private-target", tmp_path / "private-pin"
    private_pin(target)
    path.symlink_to(target)
    with pytest.raises(UnlockPinError, match="^file unavailable$"):
        UnlockPin.load(path)


def test_symlink_substituted_at_open_is_refused(tmp_path, monkeypatch):
    target, path = tmp_path / "private-target", tmp_path / "private-pin"
    private_pin(target)
    private_pin(path)
    original = os.open
    def replace_before_open(filename, flags):
        path.unlink()
        path.symlink_to(target)
        return original(filename, flags)
    monkeypatch.setattr(os, "open", replace_before_open)
    with pytest.raises(UnlockPinError, match="^file unavailable$"):
        UnlockPin.load(path)


def test_descriptor_owned_by_another_user_is_refused(tmp_path, monkeypatch):
    path = tmp_path / "private-pin"
    private_pin(path)
    original = os.fstat
    def foreign_owner(descriptor):
        metadata = original(descriptor)
        return SimpleNamespace(st_mode=metadata.st_mode, st_size=metadata.st_size,
                               st_uid=os.geteuid() + 1)
    monkeypatch.setattr(os, "fstat", foreign_owner)
    with pytest.raises(UnlockPinError, match="^current user ownership required$"):
        UnlockPin.load(path)


@pytest.mark.parametrize("growth_after_stat", [False, True])
def test_oversized_file_is_refused_even_if_it_grows_after_stat(tmp_path, monkeypatch, growth_after_stat):
    path = tmp_path / "private-pin"
    pin = private_pin(path)
    path.write_text(" " * 1024 + pin)
    if growth_after_stat:
        original = os.fstat
        def before_growth(descriptor):
            metadata = original(descriptor)
            return SimpleNamespace(st_mode=metadata.st_mode, st_uid=metadata.st_uid, st_size=0)
        monkeypatch.setattr(os, "fstat", before_growth)
    with pytest.raises(UnlockPinError, match="^file too large$"):
        UnlockPin.load(path)


def test_whitespace_at_file_size_limit_remains_accepted(tmp_path):
    path = tmp_path / "private-pin"
    pin = private_pin(path)
    path.write_text(" " * (1024 - len(pin)) + pin)
    matched = UnlockPin.load(path) == pin
    assert matched, "PIN at the file size limit did not match (contents redacted)"


def test_reader_uses_bounded_unbuffered_read(tmp_path, monkeypatch):
    path = tmp_path / "private-pin"
    pin = private_pin(path)
    original, requests, buffering = os.fdopen, [], []
    @contextmanager
    def tracked_open(descriptor, *args, **kwargs):
        buffering.append(kwargs.get("buffering"))
        with original(descriptor, *args, **kwargs) as source:
            def read(size=-1):
                requests.append(size)
                return source.read(size)
            yield SimpleNamespace(fileno=source.fileno, read=read)
    monkeypatch.setattr(os, "fdopen", tracked_open)
    matched = UnlockPin.load(path) == pin
    assert matched, "PIN did not match (contents redacted)"
    assert requests == [1025] and buffering == [0]


@pytest.mark.parametrize("length", [4, 6, 16])
def test_private_file_returns_stripped_ascii_digits_without_writing(tmp_path, length):
    path = tmp_path / "private-pin"
    pin = "".join(secrets.choice(string.digits) for _ in range(length))
    path.write_text(" \n" + pin + "\n ")
    path.chmod(0o600)
    before = path.stat().st_mtime_ns
    matched = UnlockPin.load(path) == pin
    assert matched, "PIN did not match (contents redacted)"
    assert path.stat().st_mtime_ns == before


@pytest.mark.parametrize(("failure", "reason"), [
    ("missing", "file unavailable"), ("directory", "file unavailable"),
    ("fifo", "regular file required"), ("public", "private file permissions required"),
    ("encoding", "UTF-8 required"), ("non-digit", "digits required"),
    ("short", "digits required"), ("long", "digits required"),
    ("empty", "digits required"), ("unicode", "digits required"), ("multiline", "digits required"),
])
def test_invalid_file_has_fixed_safe_reason(tmp_path, failure, reason):
    path = tmp_path / "private-pin"
    length = {"short": 3, "long": 17}.get(failure, 6)
    content = "".join(secrets.choice(string.digits) for _ in range(length))
    if failure == "directory":
        path.mkdir()
    elif failure == "fifo":
        import os
        os.mkfifo(path, 0o600)
    elif failure != "missing":
        value = {"empty": " ", "non-digit": content + "x", "unicode": "é" + content,
                 "multiline": content + "\n" + content}.get(failure, content)
        path.write_bytes(b"\xff" + content.encode() if failure == "encoding" else value.encode())
        path.chmod(0o644 if failure == "public" else 0o600)
    with pytest.raises(UnlockPinError) as refused:
        UnlockPin.load(path)
    matched = str(refused.value) == reason
    assert matched, "Unexpected refusal reason (contents redacted)"
    leaked = content in "".join(traceback.format_exception_only(refused.value))
    assert not leaked, "PIN appeared in exception diagnostics"


def test_open_failure_keeps_cause(tmp_path, monkeypatch):
    failure = PermissionError("synthetic access refusal")
    def denied(*args):
        raise failure
    monkeypatch.setattr("atomic_relay.sessions.unlock_pin.os.open", denied)
    with pytest.raises(UnlockPinError, match="^file unavailable$") as refused:
        UnlockPin.load(tmp_path / "private-pin")
    assert refused.value.__cause__ is failure


def test_reader_rereads_replaced_private_file(tmp_path):
    path = tmp_path / "private-pin"
    pins = ["".join(secrets.choice(string.digits) for _ in range(length)) for length in (5, 7)]
    for pin in pins:
        path.write_text(pin)
        path.chmod(0o600)
        matched = UnlockPin.load(path) == pin
        assert matched, "Reader retained stale PIN contents"
