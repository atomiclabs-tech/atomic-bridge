"""Pure upgrade plans enumerate only relay commands and operator-owned follow-ups."""
# no_parallel: selected-layout fixture creates a temporary operator home

from atomic_relay.ops.upgrade_plan import build_plan, render_plan, rollback_command
from ops_cases import installed


def test_plan_order_and_no_host_io(monkeypatch):
    from pathlib import Path
    with monkeypatch.context() as guarded:
        guarded.setattr(Path, "home", lambda: (_ for _ in ()).throw(AssertionError("home read")))
        guarded.setattr(Path, "exists", lambda path: (_ for _ in ()).throw(AssertionError("host read")))
        steps = build_plan()
        text = render_plan(steps)
        rollback = build_plan(rollback=True)
        rollback_text = render_plan(rollback)
    assert [step.operation for step in steps] == [
        "preflight", "backup", "secret", "env", "install", "command", "command", "health", "phone", "operator", "complete"]
    assert all(step.argv[0] == "launchctl" for step in steps if step.argv)
    assert "Operator, from a separate shell: hermes gateway restart" in text and "mac/README.md" in text
    assert "APK" in text and "verified UTC backup" in text
    assert "release APK code 10" in text and "rollback APK code 11" in text
    assert "Preserve a valid explicit host list (otherwise set loopback)" in text
    assert [step.operation for step in rollback] == ["preflight", "command", "restore", "command", "health", "phone", "operator"]
    assert "Poll /bridge/devices with the backup's admin credential, if configured (120 s)" in rollback_text


def test_selected_layout_and_exact_quoted_rollback_command(tmp_path):
    layout = installed(tmp_path)
    commands = [step.argv for step in build_plan(layout) if step.argv]
    assert commands == [("launchctl", "bootout", "gui/501/com.atomic.bridge-relay"),
                        ("launchctl", "bootstrap", "gui/501", str(layout.plist("com.atomic.bridge-relay")))]
    backup = layout.service / "backups/selected directory"
    assert rollback_command(backup) == "tools/py.sh ../tools/upgrade.py --rollback --backup '" + str(backup) + "'"
