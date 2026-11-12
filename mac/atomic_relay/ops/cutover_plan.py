"""Build the ordered operator plan without reading or changing the host."""

import shlex
from dataclasses import dataclass

from atomic_relay.ops.layout import Layout

OLD_AGENT = "com.atomic.hermes-relay"
NEW_AGENT = "com.atomic.bridge-relay"
OLD_PLUGIN = "hermes-relay"
NEW_PLUGIN = "atomic-bridge"
RESTART = "hermes gateway restart"


@dataclass(frozen=True)
class Step:
    operation: str
    description: str
    argv: tuple[str, ...] = ()
    timeout: int = 30


def build_plan(layout: Layout, *, rollback=False, verify=False) -> tuple[Step, ...]:
    """Return forward, mirror rollback, or read-only verification steps."""
    selected, other = (OLD_PLUGIN, NEW_PLUGIN) if rollback else (NEW_PLUGIN, OLD_PLUGIN)
    current, target = (NEW_AGENT, OLD_AGENT) if rollback else (OLD_AGENT, NEW_AGENT)
    if verify:
        return (
            Step("configured", f"Read configured plugins: {selected} enabled, {other} disabled",
                 ("hermes", "plugins", "list", "--json")),
            Step("doctor", f"Check installed {selected} manifest and isolated registration",
                 ("hermes", "plugins", "doctor", str(layout.plugin(selected)), "--ci"), 90),
            Step("health", f"Read /health: installed {selected} version"),
            Step("phone", "Read /bridge/devices: Atomic phone connected"),
            Step("unverified", "UNVERIFIED: active gateway loading and active-session tool visibility; Operator checks both"),
        )
    enable = ("hermes", "plugins", "enable", selected)
    if not rollback:
        enable += ("--no-allow-tool-override",)
    toggles = (
        Step("command", f"Disable {other}", ("hermes", "plugins", "disable", other)),
        Step("command", f"Enable {selected}", enable),
    )
    if rollback:
        toggles = tuple(reversed(toggles))
    preflight = (
        f"Preflight: installed files and relay.env; {current} state checked (may already be unloaded); {target} not loaded"
        if rollback else
        f"Preflight: installed files and relay.env; {current} loaded; {target} not loaded; /health from current relay"
    )
    return (
        Step("preflight", preflight),
        Step("command", f"Stop and unload {current}", ("launchctl", "bootout", layout.label(current))),
        *toggles,
        Step("command", f"Load {target}", ("launchctl", "bootstrap", f"gui/{layout.uid}", str(layout.plist(target)))),
        Step("health", f"Poll /health for installed {selected} version (60 s)", timeout=60),
        Step("phone", "Poll /bridge/devices for Atomic phone connected (120 s)", timeout=120),
        Step("operator", RESTART),
    )


def render_plan(steps: tuple[Step, ...]) -> str:
    return "\n".join(
        f"{number}. {step.description}" + (f"\n   {shlex.join(step.argv)}" if step.argv else "")
        for number, step in enumerate(steps, 1)
    )


def rollback_command(script, *, rollback=False):
    args = [str(script)] + ([] if rollback else ["--rollback"])
    return shlex.join(args)
