"""Setup actions use a temporary home, fake launchctl and virtual polling time."""
# no_parallel: private operator files and injected external-command adapters

from datetime import datetime, timezone
import logging

import pytest

from atomic_relay.ops.commands import StepFailure
from atomic_relay.ops.cutover_plan import NEW_AGENT
from atomic_relay.ops.layout import Layout
from atomic_relay.ops.pair_setup_exec import PairSetup, PairSetupError
from ops_cases import FakeClock


class Runner:
    def __init__(self, loaded=True):
        self.loaded, self.calls, self.requirements, self.fail = loaded, [], [], None

    def require(self, names):
        self.requirements.append(names)

    def run(self, argv, *, timeout=30, missing_service=False):
        self.calls.append((argv, timeout, missing_service))
        if argv[1] == self.fail:
            raise StepFailure(argv, 9, "synthetic launch failure")
        if argv[1] == "print":
            return "loaded" if self.loaded else None
        self.loaded = argv[1] == "bootstrap"
        return ""


class Probe:
    def __init__(self):
        self.calls, self.ok = [], True

    def get(self, path, *, timeout=5):
        self.calls.append((path, timeout))
        return {"status": "ok" if self.ok else "unavailable"}


@pytest.fixture
def setup_case(tmp_path):
    layout = Layout(tmp_path / "isolated home", 42)
    layout.service.mkdir(parents=True)
    env = layout.service / "relay.env"
    env.write_bytes(b"# retained\r\nATOMIC_RELAY_HOST=127.0.0.1\r\nUNRELATED='keep # bytes'\r\n")
    env.chmod(0o600)
    plist = layout.plist(NEW_AGENT)
    plist.parent.mkdir(parents=True)
    plist.write_text("isolated never-executed plist")
    runner, probe, clock, output = Runner(), Probe(), FakeClock(), []
    setup = PairSetup(layout, runner, probe, clock.clock, clock.sleep, output.append,
                      now=lambda: datetime(2030, 1, 2, 3, 4, 5, tzinfo=timezone.utc))
    return setup, env, runner, probe, clock, output


def test_loaded_apply_backs_up_updates_and_restarts_only_relay(setup_case, monkeypatch):
    from atomic_relay.ops import pair_setup_exec
    setup, env, runner, probe, clock, output = setup_case
    before, writes = env.read_bytes(), []
    original = pair_setup_exec.envfile.update
    def update(path, values):
        writes.append((path, values))
        return original(path, values)
    monkeypatch.setattr(pair_setup_exec.envfile, "update", update)
    backup = setup.apply("100.64.1.2")
    assert backup == env.with_name("relay.env.bak-20300102T030405.000000Z")
    assert backup.read_bytes() == before and backup.stat().st_mode & 0o777 == 0o600
    assert writes == [(env, {"ATOMIC_RELAY_HOST": "127.0.0.1,100.64.1.2"})]
    assert b"ATOMIC_RELAY_HOST='127.0.0.1,100.64.1.2'\r\n" in env.read_bytes()
    assert b"UNRELATED='keep # bytes'\r\n" in env.read_bytes()
    assert env.stat().st_mode & 0o777 == 0o600
    label = setup.layout.label(NEW_AGENT)
    assert runner.calls == [(("launchctl", "print", label), 30, True),
                            (("launchctl", "bootout", label), 30, True),
                            (("launchctl", "bootstrap", "gui/42", str(setup.layout.plist(NEW_AGENT))), 30, False)]
    assert runner.requirements == [("launchctl",)] and probe.calls == [("/health", 5)]
    assert str(backup) in "\n".join(output) and not clock.sleeps


def test_missing_apply_bootstraps_without_bootout(setup_case):
    setup, env, runner, probe, clock, output = setup_case
    runner.loaded = False
    setup.apply("192.168.1.5")
    assert [argv[1] for argv, _, _ in runner.calls] == ["print", "bootstrap"]
    assert probe.calls == [("/health", 5)]


@pytest.mark.parametrize("loaded", [False, True])
def test_ensure_loaded_is_a_caller_approved_start_without_env_change(setup_case, loaded):
    setup, env, runner, probe, clock, output = setup_case
    runner.loaded = loaded
    before = env.read_bytes()
    assert setup.ensure_loaded() is (not loaded)
    assert [argv[1] for argv, _, _ in runner.calls] == (["print"] if loaded else ["print", "bootstrap"])
    assert bool(probe.calls) is (not loaded)
    assert env.read_bytes() == before and not list(env.parent.glob("relay.env.bak-*"))


def test_constructor_is_inert_until_caller_invokes_an_approved_action(setup_case):
    setup, env, runner, probe, clock, output = setup_case
    before, inode = env.read_bytes(), env.stat().st_ino
    assert env.read_bytes() == before and env.stat().st_ino == inode
    assert not runner.calls and not probe.calls and not output
    assert not list(env.parent.glob("relay.env.bak-*"))


def test_same_address_twice_makes_no_second_backup_or_env_change(setup_case, monkeypatch):
    from atomic_relay.ops import pair_setup_exec
    setup, env, runner, probe, clock, output = setup_case
    backup = setup.apply("100.64.1.2")
    data, inode = env.read_bytes(), env.stat().st_ino
    def forbidden(*args, **kwargs):
        raise AssertionError("unchanged address attempted env write")
    monkeypatch.setattr(pair_setup_exec.envfile, "update", forbidden)
    assert setup.apply("100.64.1.2") is None
    assert env.read_bytes() == data and env.stat().st_ino == inode
    assert list(env.parent.glob("relay.env.bak-*")) == [backup]
    assert len(probe.calls) == 2


def test_unhealthy_relay_exhausts_budget_and_reports_backup_with_cause(setup_case, caplog):
    setup, env, runner, probe, clock, output = setup_case
    before = env.read_bytes()
    probe.ok = False
    with caplog.at_level(logging.ERROR, logger="atomic_relay.ops"), pytest.raises(PairSetupError) as caught:
        setup.apply("100.64.1.2")
    assert sum(clock.sleeps) == 60 and len(probe.calls) == 61
    assert all(path == "/health" and 0 < timeout <= 5 for path, timeout in probe.calls)
    assert isinstance(caught.value.__cause__, ValueError)
    assert setup.backup.read_bytes() == before and str(setup.backup) in str(caught.value)
    assert str(setup.backup) in "\n".join(output)
    assert any(record.exc_info for record in caplog.records)
    assert "100.64.1.2" not in caplog.text


@pytest.mark.parametrize("operation", ["print", "bootout", "bootstrap"])
def test_command_failure_stops_before_health_and_names_backup(setup_case, operation):
    setup, env, runner, probe, clock, output = setup_case
    runner.fail = operation
    with pytest.raises(PairSetupError) as caught:
        setup.apply("100.64.1.2")
    assert isinstance(caught.value.__cause__, StepFailure) and caught.value.__cause__.code == 9
    assert str(setup.backup) in str(caught.value) and not probe.calls
    assert runner.calls[-1][0][1] == operation


def test_missing_service_start_failure_is_not_swallowed(setup_case):
    setup, env, runner, probe, clock, output = setup_case
    runner.loaded, runner.fail = False, "bootstrap"
    before = env.read_bytes()
    with pytest.raises(PairSetupError) as caught:
        setup.ensure_loaded()
    assert isinstance(caught.value.__cause__, StepFailure) and env.read_bytes() == before
    assert not probe.calls and not list(env.parent.glob("relay.env.bak-*"))


@pytest.mark.parametrize("target", ["env", "plist"])
def test_symlink_input_refused_before_backup_or_service_commands(setup_case, target, tmp_path):
    setup, env, runner, probe, clock, output = setup_case
    path = env if target == "env" else setup.layout.plist(NEW_AGENT)
    data = path.read_bytes()
    actual = tmp_path / "symlink-target"
    actual.write_bytes(data)
    path.unlink()
    path.symlink_to(actual)
    with pytest.raises(PairSetupError):
        setup.apply("100.64.1.2")
    assert actual.read_bytes() == data and not runner.calls and not probe.calls
    assert not list(env.parent.glob("relay.env.bak-*"))


def test_backup_collision_never_overwrites_backup_or_environment(setup_case):
    setup, env, runner, probe, clock, output = setup_case
    collision = env.with_name("relay.env.bak-20300102T030405.000000Z")
    collision.write_bytes(b"earlier isolated backup")
    before = env.read_bytes()
    with pytest.raises(PairSetupError) as caught:
        setup.apply("100.64.1.2")
    assert isinstance(caught.value.__cause__, FileExistsError)
    assert collision.read_bytes() == b"earlier isolated backup" and env.read_bytes() == before
    assert not runner.calls and not probe.calls


@pytest.mark.parametrize("operation", ["backup", "env"])
def test_file_write_failure_preserves_env_and_stops_before_service_actions(setup_case, monkeypatch, operation):
    from atomic_relay.ops import pair_setup_exec
    setup, env, runner, probe, clock, output = setup_case
    before = env.read_bytes()
    original = pair_setup_exec.envfile.atomic_write
    def fail(path, data):
        if (path == env) is (operation == "env"):
            raise OSError("synthetic file write failure")
        original(path, data)
    monkeypatch.setattr(pair_setup_exec.envfile, "atomic_write", fail)
    with pytest.raises(PairSetupError) as caught:
        setup.apply("100.64.1.2")
    assert isinstance(caught.value.__cause__, OSError) and env.read_bytes() == before
    assert not runner.calls and not probe.calls
    if operation == "env":
        assert setup.backup.read_bytes() == before and str(setup.backup) in str(caught.value)
    else:
        assert setup.backup is None and not list(env.parent.glob("relay.env.bak-*"))


def test_retry_after_failed_bootout_keeps_one_backup_and_retries_restart(setup_case):
    setup, env, runner, probe, clock, output = setup_case
    runner.fail = "bootout"
    with pytest.raises(PairSetupError):
        setup.apply("100.64.1.2")
    backup, data, inode = setup.backup, env.read_bytes(), env.stat().st_ino
    runner.fail = None
    assert setup.apply("100.64.1.2") is None
    assert [argv[1] for argv, _, _ in runner.calls] == ["print", "bootout", "print", "bootout", "bootstrap"]
    assert list(env.parent.glob("relay.env.bak-*")) == [backup]
    assert env.read_bytes() == data and env.stat().st_ino == inode and probe.calls == [("/health", 5)]
