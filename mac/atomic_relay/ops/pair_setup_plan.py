"""Describe the caller-approved relay address setup without host access."""

import ipaddress

from atomic_relay.config_network import parse_phone_url
from atomic_relay.ops.cutover_plan import NEW_AGENT, Step, render_plan as _render_plan


def build_plan(layout, address) -> tuple[Step, ...]:
    """Require a non-loopback, non-link-local private IPv4 address."""
    try:
        parsed = ipaddress.IPv4Address(address)
        if parsed.is_loopback or parsed.is_link_local:
            raise ValueError("address is not a discovery candidate")
        parse_phone_url(f"ws://{parsed}")
    except ValueError as error:
        error.args = ("invalid setup address",)
        raise ValueError("component=ops operation=pair-setup requires a private IPv4 address") from error
    return (
        Step("backup", "Copy relay.env to relay.env.bak-<UTC>"),
        Step("env", f"Set ATOMIC_RELAY_HOST=127.0.0.1,{parsed} in relay.env"),
        Step("restart", f"Restart {layout.label(NEW_AGENT)} (bootout if loaded, then bootstrap)"),
        Step("health", "Poll /health (60 s)", timeout=60),
    )


def render_plan(steps: tuple[Step, ...]) -> str:
    return _render_plan(steps)
