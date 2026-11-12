# no_parallel: opens a loopback test server on port 0.
import json
import logging

import pytest

from atomic_bridge import tools_apps
from atomic_bridge.client import RelayClient
from fake_relay import admin_environment, FakeRelay, FakeResponse
from tool_cases import CASES, assert_wire_case, install_client


@pytest.mark.parametrize("device", [False, True])
@pytest.mark.parametrize("case", [case for case in CASES if case[0] in tools_apps.HANDLERS], ids=lambda case: case[0])
def test_apps_wire_matches_upstream(monkeypatch, case, device):
    assert_wire_case(monkeypatch, case, device)


@pytest.mark.parametrize("name,args", [("android_events", {}), ("android_event_stream", {"enabled": True})])
def test_phone_failures_are_never_replaced_with_success(monkeypatch, name, args):
    value = {"success": False, "code": "unsupported_command", "error": "synthetic"}
    with FakeRelay(lambda request: FakeResponse(value=value)) as relay:
        install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()}))
        assert json.loads(tools_apps.HANDLERS[name](args)) == value


def test_events_defaults_and_cap(monkeypatch, caplog):
    calls = []
    monkeypatch.setattr(tools_apps, "call", lambda method, path, args, body: calls.append(body) or '{}')
    tools_apps.android_events({})
    with caplog.at_level(logging.WARNING, logger="atomic_bridge"):
        tools_apps.android_events({"limit": 999})
    assert calls == [{"limit": 50, "since": 0}, {"limit": 500, "since": 0}]
    assert "clamped" in caplog.text


@pytest.mark.parametrize("name,args", [
    ("android_events", {"limit": 0}), ("android_events", {"since": -1}),
    ("android_event_stream", {"enabled": "yes"}), ("android_media", {"action": "invalid"}),
])
def test_invalid_apps_inputs_return_errors_without_transport(monkeypatch, name, args):
    monkeypatch.setattr(tools_apps, "call", lambda *args: pytest.fail("Invalid input reached transport"))
    assert "error" in json.loads(tools_apps.HANDLERS[name](args))
