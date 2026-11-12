import logging
import subprocess

import pytest

from atomic_relay.ops.commands import CommandRunner, StepFailure


def test_subprocess_result_is_checked_and_bounded(monkeypatch):
    seen = []
    def execute(argv, **kwargs):
        seen.append((argv, kwargs))
        return subprocess.CompletedProcess(argv, 0, "captured", "")
    monkeypatch.setattr(subprocess, "run", execute)
    assert CommandRunner().run(("fake",), timeout=7) == "captured"
    assert seen == [(("fake",), {"check": True, "timeout": 7, "capture_output": True, "text": True})]


@pytest.mark.parametrize("error,code", [
    (subprocess.CalledProcessError(9, ["fake"], output="synthetic stdout", stderr="synthetic stderr"), 9),
    (subprocess.TimeoutExpired(["fake"], 1, output=b"timed out", stderr=b"diagnostic"), 124),
    (FileNotFoundError("not installed"), 127),
])
def test_failures_keep_cause_diagnostics_and_error_trace(monkeypatch, caplog, error, code):
    def failed(*args, **kwargs):
        raise error
    monkeypatch.setattr(subprocess, "run", failed)
    with caplog.at_level(logging.ERROR, logger="atomic_relay.ops"), pytest.raises(StepFailure) as caught:
        CommandRunner().run(("fake",))
    assert caught.value.code == code and caught.value.__cause__ is error
    assert any(record.exc_info for record in caplog.records)


def test_only_explicit_expected_absent_service_is_accepted(monkeypatch):
    argv = ("launchctl", "print", "gui/501/example")
    def absent(*args, **kwargs):
        raise subprocess.CalledProcessError(113, argv, stderr='Could not find service "example" in domain for user gui: 501')
    monkeypatch.setattr(subprocess, "run", absent)
    assert CommandRunner().run(argv, missing_service=True) is None
    with pytest.raises(StepFailure):
        CommandRunner().run(argv)
    def denied(*args, **kwargs):
        raise subprocess.CalledProcessError(113, argv, stderr="permission denied")
    monkeypatch.setattr(subprocess, "run", denied)
    with pytest.raises(StepFailure):
        CommandRunner().run(argv, missing_service=True)


def test_missing_command_preflight_fails(monkeypatch):
    monkeypatch.setattr("atomic_relay.ops.commands.shutil.which", lambda name: None)
    with pytest.raises(FileNotFoundError, match="missing"):
        CommandRunner().require(("fake",))
