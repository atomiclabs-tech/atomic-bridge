"""The address setup plan is pure and contains only relay operations."""

from pathlib import Path

import pytest

from atomic_relay.ops.cutover_plan import Step
from atomic_relay.ops.layout import Layout
from atomic_relay.ops.pair_setup_plan import build_plan, render_plan


def test_plan_order_descriptions_and_timeout_without_host_io(monkeypatch, tmp_path):
    def forbidden(*args, **kwargs):
        raise AssertionError("plan attempted filesystem I/O")
    monkeypatch.setattr(Path, "read_bytes", forbidden)
    monkeypatch.setattr(Path, "read_text", forbidden)
    steps = build_plan(Layout(tmp_path, 42), "100.64.1.2")
    assert all(isinstance(step, Step) for step in steps)
    assert [step.operation for step in steps] == ["backup", "env", "restart", "health"]
    assert steps[0].description == "Copy relay.env to relay.env.bak-<UTC>"
    assert steps[1].description == "Set ATOMIC_RELAY_HOST=127.0.0.1,100.64.1.2 in relay.env"
    assert "bootout if loaded" in steps[2].description and "bootstrap" in steps[2].description
    assert steps[3].timeout == 60 and "/health" in steps[3].description
    text = render_plan(steps)
    assert len(text.splitlines()) == 4 and text.startswith("1. Copy relay.env")
    assert all(word not in text for word in ("hermes", "gateway", "kickstart"))


@pytest.mark.parametrize("address", ["0.0.0.0", "127.0.0.1", "169.254.1.2", "8.8.8.8",
                                     "192.0.2.1", "::1", "relay.local", "10.1.2.3\nunsafe"])
def test_plan_refuses_non_private_ipv4_without_address_in_error(tmp_path, address):
    with pytest.raises(ValueError, match="private IPv4") as caught:
        build_plan(Layout(tmp_path, 42), address)
    assert address not in str(caught.value) + str(caught.value.__cause__)
