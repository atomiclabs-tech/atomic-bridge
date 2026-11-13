"""Describe a reversible relay upgrade without inspecting the host."""

import shlex
from pathlib import Path

from atomic_relay.ops.cutover_plan import NEW_AGENT, RESTART, Step, render_plan
from atomic_relay.ops.layout import Layout


def build_plan(layout=None, *, rollback=False):
    """Return only relay commands; gateway and device work remains operator-owned."""
    layout = layout if layout is not None else Layout(Path("~"), "<uid>")
    stop = Step("command", "Unload the relay only", ("launchctl", "bootout", layout.label(NEW_AGENT)))
    start = Step("command", "Load the relay only",
                 ("launchctl", "bootstrap", f"gui/{layout.uid}", str(layout.plist(NEW_AGENT))))
    if rollback:
        return (
            Step("preflight", "Validate selected backup (default: newest) and its recorded relay version"),
            stop, Step("restore", "Restore relay package, pyproject.toml, relay.env and Atomic plugin; retain admin.secret"),
            start, Step("health", "Poll public /health for backed-up version (60 s)", timeout=60),
            Step("phone", "Poll /bridge/devices with the backup's admin credential, if configured (120 s)", timeout=120),
            Step("operator", f"Operator, from a separate shell: {RESTART}"),
        )
    return (
        Step("preflight", "Validate installed files, loaded relay and public /health"),
        Step("backup", "Reuse a valid started backup or atomically publish a verified UTC backup"),
        Step("secret", "Create admin.secret only if missing (0600)"),
        Step("env", "Preserve a valid explicit host list (otherwise set loopback), and atomically set trusted proxy, admin secret path and target version in relay.env"),
        Step("install", "Install packages, pyproject.toml, offline venv fingerprint and plist"),
        stop, start,
        Step("health", "Poll public /health for target version (60 s)", timeout=60),
        Step("phone", "Poll /bridge/devices with the admin credential (120 s)", timeout=120),
        Step("operator", f"Watcher edit: mac/README.md; Operator, from a separate shell: {RESTART}; then build/check rollback APK code 11 before installing release APK code 10"),
        Step("complete", "Mark the verified backup complete"),
    )


def rollback_command(backup):
    return "tools/py.sh ../tools/upgrade.py --rollback --backup " + shlex.quote(str(backup))


__all__ = ["build_plan", "render_plan", "rollback_command"]
