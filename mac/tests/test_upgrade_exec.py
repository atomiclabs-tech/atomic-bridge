"""Attack case 16: interrupt every step, retry safely, and restore exact snapshots."""
# no_parallel: fake command/probe upgrade operates on temporary home files

import json
import logging
from pathlib import Path

import pytest

from atomic_relay import __version__
from atomic_relay.ops.commands import StepFailure
from atomic_relay.ops.upgrade_exec import Upgrade
from atomic_relay.ops.upgrade_plan import build_plan, rollback_command
from upgrade_cases import admin_backup_case, backup_dir, case, snapshot


@pytest.fixture(autouse=True)
def capture_operator_logger(caplog):
    logger = logging.getLogger("atomic_relay.ops")
    previous = logger.propagate
    logger.propagate = False
    logger.addHandler(caplog.handler)
    yield
    logger.removeHandler(caplog.handler)
    logger.propagate = previous


@pytest.mark.parametrize("failed_index", range(11))
def test_attack_16_every_forward_step_stops_with_redacted_exact_rollback_and_retry(tmp_path, monkeypatch, caplog, failed_index):
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    before = snapshot(layout)
    original_plan = build_plan(layout)
    secret_body = "synthetic-response-credential-must-never-escape-" * 2
    seen = []
    def interrupted_plan(*args, **kwargs):
        for index, step in enumerate(original_plan):
            if index == failed_index:
                raise StepFailure(("fake-step", step.operation), 17, secret_body)
            seen.append(step.operation)
            yield step
    monkeypatch.setattr("atomic_relay.ops.upgrade_exec.build_plan", interrupted_plan)
    with caplog.at_level(logging.ERROR, logger="atomic_relay.ops"):
        assert module.main([], **options) == 1
    assert len(seen) == failed_index
    text = "\n".join(output)
    assert "command: fake-step" in text and "exit code: 17" in text and "output:" in text
    assert rollback_command(backup_dir(layout)) in text and secret_body not in text + caplog.text
    assert any(record.exc_info for record in caplog.records)
    backup = backup_dir(layout)
    usable = backup.is_dir()
    if not usable:
        assert "No usable backup exists yet" in text and snapshot(layout) == before
    else:
        assert json.loads((backup / "upgrade-state.json").read_text())["stage"] == "started"
    secret = layout.service / "admin.secret"
    retained_secret = secret.read_bytes() if secret.exists() else None
    retained_env = (layout.service / "relay.env").read_bytes()
    env_inode = (layout.service / "relay.env").stat().st_ino
    monkeypatch.setattr("atomic_relay.ops.upgrade_exec.build_plan", build_plan)
    probe.versions = [__version__]
    assert module.main([], **options) == 0
    assert list((layout.service / "backups").iterdir()) == [backup]
    if retained_secret is not None:
        assert secret.read_bytes() == retained_secret
    if failed_index >= 4:
        assert (layout.service / "relay.env").read_bytes() == retained_env
        assert (layout.service / "relay.env").stat().st_ino == env_inode
    assert json.loads((backup / "upgrade-state.json").read_text())["stage"] == "complete"
    assert (backup / "relay.env").stat().st_mode & 0o777 == 0o600
    probe.versions = ["1.19.3"]
    assert module.main(["--rollback", "--backup", str(backup)], **options) == 0
    assert snapshot(layout) == before and secret.is_file()
    assert all(path.read_bytes() == data for path, data in sentinels.items())
    for argv, timeout in runner.calls:
        assert timeout > 0 and argv[0] in {"launchctl", "fake-uv", str(layout.service / ".venv/bin/python")}
        assert argv[0] != "hermes"
        assert not any(str(path) in part for path in sentinels for part in argv)
        if argv[0] == "launchctl":
            assert argv[1] in {"print", "bootout", "bootstrap"}
    assert any(path == "/bridge/devices" and credential == secret for path, credential in probe.credentials)
    assert probe.credentials[-2:] == [("/health", None), ("/bridge/devices", None)]


def test_failure_inside_backup_cleans_staging_before_any_product_mutation(tmp_path, monkeypatch):
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    original = snapshot(layout)
    def fail(*args, **kwargs):
        raise OSError("synthetic snapshot copy failure")
    monkeypatch.setattr("atomic_relay.ops.upgrade_exec.sync_package", fail)
    assert module.main([], **options) == 1
    assert snapshot(layout) == original and not (layout.service / "admin.secret").exists()
    assert list((layout.service / "backups").iterdir()) == []
    assert "No usable backup exists yet" in "\n".join(output)
    assert [argv[1] for argv, _ in runner.calls] == ["print"]


def test_probe_failure_stops_before_later_steps_and_has_bounded_poll(tmp_path, caplog):
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    probe.connected = False
    assert module.main([], **options) == 1
    assert sum(options["sleep"].__self__.sleeps) == 120
    assert json.loads((backup_dir(layout) / "upgrade-state.json").read_text())["stage"] == "started"
    assert not any("Accepted window" in line for line in output)


def test_missing_preflight_and_invalid_configuration_print_planned_rollback(tmp_path):
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    (layout.service / ".venv/bin/python").unlink()
    assert module.main([], **options) == 1
    assert not backup_dir(layout).exists() and not runner.calls
    assert rollback_command(backup_dir(layout)) in "\n".join(output)
    output.clear()
    (layout.service / "relay.env").write_text("ATOMIC_RELAY_PORT=invalid\n")
    assert module.main([], **options) == 1
    assert "No usable backup exists yet" in "\n".join(output)
    assert rollback_command(backup_dir(layout)) in "\n".join(output)


def test_tampered_backup_refused_before_rollback_commands(tmp_path):
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    assert module.main([], **options) == 0
    backup = backup_dir(layout)
    (backup / "relay.env").write_bytes(b"corrupt snapshot")
    runner.calls.clear()
    assert module.main(["--rollback"], **options) == 1
    assert not runner.calls


@pytest.mark.parametrize("operation", ["preflight", "backup", "secret", "env", "install", "bootout", "bootstrap",
                                       "health", "phone", "operator", "complete"])
def test_failure_inside_each_forward_dependency_stops_immediately(tmp_path, monkeypatch, caplog, operation):
    import atomic_relay.ops.upgrade_exec as implementation
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    calls = []
    private_response = "synthetic-private-response-" * 3
    def fail(*args, **kwargs):
        calls.append(operation)
        raise StepFailure(("fake-dependency", operation), 23, private_response)
    if operation in {"backup", "secret", "env"}:
        name = {"backup": "sync_package", "secret": "create_if_missing", "env": "update"}[operation]
        monkeypatch.setattr(implementation, name, fail)
    elif operation == "install":
        monkeypatch.setattr(options["installer"], "install", fail)
    elif operation in {"preflight", "bootout", "bootstrap"}:
        original = runner.run
        def command(argv, **kwargs):
            if argv[0] == "launchctl" and argv[1] == ("print" if operation == "preflight" else operation):
                return fail()
            return original(argv, **kwargs)
        monkeypatch.setattr(runner, "run", command)
    elif operation in {"health", "phone"}:
        original = probe.get
        def get(path, **kwargs):
            if (operation == "phone" and path == "/bridge/devices") or (
                    operation == "health" and path == "/health" and probe.calls):
                return fail()
            return original(path, **kwargs)
        monkeypatch.setattr(probe, "get", get)
    elif operation == "operator":
        def emit(line):
            if line.startswith("Operator, from a separate shell"):
                return fail()
            output.append(line)
        options["emit"] = emit
    else:
        original = implementation.atomic_write
        def write(path, data):
            if path.name == "upgrade-state.json" and json.loads(data)["stage"] == "complete":
                return fail()
            return original(path, data)
        monkeypatch.setattr(implementation, "atomic_write", write)
    with caplog.at_level(logging.ERROR, logger="atomic_relay.ops"):
        assert module.main([], **options) == 1
    assert calls == [operation]
    assert "exit code: 23" in "\n".join(output)
    assert "synthetic-private-response-" not in "\n".join(output) + caplog.text
    assert rollback_command(backup_dir(layout)) in "\n".join(output)
    if backup_dir(layout).exists():
        assert json.loads((backup_dir(layout) / "upgrade-state.json").read_text())["stage"] == "started"


def test_rollback_interrupted_forward_defaults_newest_and_warns_on_checked_missing_service(tmp_path, monkeypatch, caplog):
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    before = snapshot(layout)
    original = runner.run
    def interrupted(argv, **kwargs):
        if argv[:2] == ("launchctl", "bootstrap"):
            raise StepFailure(argv, 13, "synthetic bootstrap failure")
        return original(argv, **kwargs)
    monkeypatch.setattr(runner, "run", interrupted)
    assert module.main([], **options) == 1
    secret = (layout.service / "admin.secret").read_bytes()
    def absent(argv, **kwargs):
        if argv[:2] == ("launchctl", "bootout"):
            assert kwargs["missing_service"] is True
            return None
        return original(argv, **kwargs)
    monkeypatch.setattr(runner, "run", absent)
    probe.versions = ["1.19.3"]
    with caplog.at_level(logging.WARNING, logger="atomic_relay.ops"):
        assert module.main(["--rollback"], **options) == 0
    assert snapshot(layout) == before and (layout.service / "admin.secret").read_bytes() == secret
    assert "already unloaded" in caplog.text


def test_rollback_uses_verified_snapshot_settings_when_current_install_is_damaged(tmp_path):
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    before = snapshot(layout)
    assert module.main([], **options) == 0
    (layout.service / "relay.env").write_bytes(b"not an environment assignment")
    (layout.service / "pyproject.toml").unlink()
    (layout.service / "atomic_relay/__main__.py").unlink()
    probe.versions = ["1.19.3"]
    assert module.main(["--rollback"], **options) == 0
    assert snapshot(layout) == before


def test_preflight_accepts_uv_python_symlink_and_closing_steps_stay_operator_owned(tmp_path):
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    python = layout.service / ".venv/bin/python"
    target = python.parent / "fake-python-target"
    python.rename(target)
    python.symlink_to(target)
    assert module.main([], **options) == 0
    text = "\n".join(output)
    for expected in ("mac/README.md", "Operator, from a separate shell: hermes gateway restart",
                     "agent tools return 401", "phone link stays up", "tools/build-rollback-apk.sh",
                     "tools/check-release-apk.sh", "tools/build.sh :app:assembleRelease"):
        assert expected in text
    assert all(argv[0] not in {"adb", "hermes"} for argv, _ in runner.calls)
    assert "tools/build-rollback-apk.sh b4dfab2 9" in text
    assert "Atomic-rollback-b4dfab2-9.apk --version-code 9 --debuggable" in text
    assert "app-release.apk --version-code 8" in text


def test_abandoned_unpublished_staging_does_not_block_a_fresh_verified_backup(tmp_path):
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    staging = layout.service / "backups/.upgrade-interrupted-copy"
    staging.mkdir(parents=True)
    (staging / "partial").write_bytes(b"incomplete private snapshot")
    assert module.main([], **options) == 0
    assert backup_dir(layout).is_dir() and staging.is_dir()
    assert not any("Reusing started backup" in line for line in output)


def test_symlinked_snapshot_package_is_not_a_usable_backup(tmp_path):
    import shutil
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    assert module.main([], **options) == 0
    backup = backup_dir(layout)
    shutil.rmtree(backup / "atomic_relay")
    (backup / "atomic_relay").symlink_to(layout.service / "atomic_relay", target_is_directory=True)
    runner.calls.clear()
    assert module.main(["--rollback"], **options) == 1 and not runner.calls


@pytest.mark.parametrize("home_relative", [False, True], ids=["absolute", "home-relative"])
def test_rollback_admin_backup_uses_the_restored_credential(tmp_path, home_relative):
    context, secret = admin_backup_case(tmp_path, home_relative=home_relative)
    module, layout, runner, probe, output, options, sentinels = context
    before, retained = snapshot(layout), secret.read_bytes()
    assert secret != layout.service / "admin.secret"
    assert module.main([], **options) == 0
    probe.expected_secret_file, probe.versions = secret, ["2.2.0"]
    probe.credentials.clear()
    assert module.main(["--rollback"], **options) == 0
    assert probe.credentials == [("/health", None), ("/bridge/devices", secret)]
    assert snapshot(layout) == before
    secret_retained = secret.read_bytes() == retained
    assert secret_retained
    assert all(path.read_bytes() == data for path, data in sentinels.items())


@pytest.mark.parametrize("explicit_empty", [False, True], ids=["absent", "empty"])
def test_rollback_legacy_backup_omits_admin_bearer(tmp_path, explicit_empty):
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    if explicit_empty:
        env = layout.service / "relay.env"
        env.write_text(env.read_text() + "ATOMIC_RELAY_ADMIN_SECRET_FILE=\n")
    before = snapshot(layout)
    assert module.main([], **options) == 0
    probe.versions = ["1.19.3"]
    probe.credentials.clear()
    assert module.main(["--rollback"], **options) == 0
    assert probe.credentials == [("/health", None), ("/bridge/devices", None)]
    assert snapshot(layout) == before and (layout.service / "admin.secret").is_file()


def test_rollback_admin_refusal_exits_with_the_rollback_command(tmp_path, caplog):
    context, secret = admin_backup_case(tmp_path)
    module, layout, runner, probe, output, options, sentinels = context
    assert module.main([], **options) == 0
    probe.expected_secret_file, probe.versions, probe.refuse = secret, ["2.2.0"], True
    probe.credentials.clear()
    output.clear()
    caplog.clear()
    assert module.main(["--rollback"], **options) == 1
    attempts = [credential for path, credential in probe.credentials if path == "/bridge/devices"]
    assert len(attempts) == 121
    assert all(credential == secret for credential in attempts)
    assert probe.credentials[0] == ("/health", None)
    assert sum(options["sleep"].__self__.sleeps) == 120
    assert "Rollback command: " + rollback_command(backup_dir(layout)) in "\n".join(output)
    assert any(record.exc_info for record in caplog.records)


@pytest.mark.parametrize("value", ["127.0.0.1, 100.64.1.2", "::1,127.0.0.1", " 127.0.0.1,127.0.0.1 "],
                         ids=["tailnet", "ipv6", "duplicate-whitespace"])
def test_upgrade_preserves_valid_host_assignment_exactly(tmp_path, value):
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    env = layout.service / "relay.env"
    assignment = f"ATOMIC_RELAY_HOST='{value}'\r\n".encode()
    env.write_bytes(env.read_bytes() + assignment)
    assert module.main([], **options) == 0
    assert assignment in env.read_bytes()


@pytest.mark.parametrize("value", [None, "", "0.0.0.0", "::", "relay.local", "100.64.1.2"])
def test_upgrade_resets_missing_or_invalid_host_assignment(tmp_path, value):
    module, layout, runner, probe, output, options, sentinels = case(tmp_path)
    env = layout.service / "relay.env"
    if value is not None:
        env.write_text(env.read_text() + f"ATOMIC_RELAY_HOST='{value}'\n")
    assert module.main([], **options) == 0
    assert b"ATOMIC_RELAY_HOST=127.0.0.1\n" in env.read_bytes()
