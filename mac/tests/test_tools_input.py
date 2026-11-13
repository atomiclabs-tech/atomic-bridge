# no_parallel: opens a loopback test server on port 0.
import json
import logging
import secrets
import string

import pytest

import atomic_bridge
from atomic_bridge import tools_input
from atomic_bridge.client import RelayClient
from atomic_bridge.tool_support import ToolInputError
from fake_relay import admin_environment, FakeRelay, FakeResponse
from recording_ctx import RecordingContext
from tool_cases import CASES, assert_wire_case, install_client


@pytest.mark.parametrize("device", [False, True])
def test_unlock_registered_handler_uses_admin_post_without_pin(monkeypatch, device):
    ctx = RecordingContext()
    atomic_bridge.register(ctx)
    tool = next((entry for entry in ctx.tools if entry["name"] == "android_unlock"), None)
    assert tool is not None, "android_unlock is not registered"
    result = {"success": True, "unlocked": True}
    args = {"device": "synthetic-device"} if device else {}
    with FakeRelay(lambda request: FakeResponse(value=result)) as relay:
        monkeypatch.setenv("ANDROID_BRIDGE_URL", relay.url)
        actual = tool["handler"](args, session_id="synthetic", task_id="synthetic")
        assert isinstance(actual, str) and json.loads(actual) == result
        assert len(relay.requests) == 1 and relay.rejected == 0
        request = relay.requests[0]
        assert (request["method"], request["path"]) == ("POST", "/unlock")
        assert request["body"] == args and request["query"] == {}
        authorized = request["headers"].get("Authorization") == "Bearer " + relay.expected_secret
        assert authorized, "Admin bearer did not match (credential redacted)"


@pytest.mark.parametrize("kind", ["generated", "null", "empty"])
def test_unlock_rejects_any_pin_argument_without_http(monkeypatch, kind, caplog):
    pin = "".join(secrets.choice(string.digits) for _ in range(12))
    value = {"generated": pin, "null": None, "empty": ""}[kind]
    args = {"pin": value, "device": "synthetic-device"}
    message = "pin is not accepted; configure ATOMIC_RELAY_UNLOCK_PIN_FILE on the relay"
    with FakeRelay() as relay:
        monkeypatch.setenv("ANDROID_BRIDGE_URL", relay.url)
        with pytest.raises(ToolInputError) as refused:
            tools_input.android_unlock.__wrapped__(args)
        matched = str(refused.value) == message
        assert matched, "Unexpected refusal (contents redacted)"
        actual = tools_input.android_unlock(args, session_id="synthetic")
        matched = isinstance(actual, str) and json.loads(actual) == {"error": message}
        assert matched, "Unexpected refusal envelope (contents redacted)"
        sent = bool(relay.requests)
        assert not sent, "A refused PIN argument reached HTTP"
    leaked = pin in caplog.text + actual
    assert not leaked, "PIN appeared in diagnostics (contents redacted)"


def test_unlock_reports_relay_refusal_with_existing_http_envelope(monkeypatch):
    with FakeRelay(lambda request: FakeResponse(status=503, value={"error": "unlock PIN not configured"})) as relay:
        monkeypatch.setenv("ANDROID_BRIDGE_URL", relay.url)
        actual = json.loads(tools_input.android_unlock({}))
        assert actual == {"error": "HTTP 503: unlock PIN not configured", "status": 503}
        assert len(relay.requests) == 1


@pytest.mark.parametrize("result", [
    {"success": True, "unlocked": True},
    {"success": True, "unlocked": True, "already_unlocked": True},
    {"success": False, "code": "keyguard_blocked", "error": "PIN not accepted.",
     "stage": "pin_not_accepted", "pin_attempt_used": True},
    {"success": False, "code": "wake_failed", "error": "The screen did not wake.",
     "stage": "wake", "pin_attempt_used": False},
])
def test_unlock_preserves_phone_success_and_failure_fields(monkeypatch, result):
    with FakeRelay(lambda request: FakeResponse(value=result)) as relay:
        monkeypatch.setenv("ANDROID_BRIDGE_URL", relay.url)
        actual = tools_input.android_unlock({})
        assert isinstance(actual, str) and json.loads(actual) == result
        assert len(relay.requests) == 1


@pytest.mark.parametrize("device", [False, True])
@pytest.mark.parametrize("case", [case for case in CASES if case[0] in tools_input.HANDLERS], ids=lambda case: case[0])
def test_input_wire_matches_upstream(monkeypatch, case, device):
    assert_wire_case(monkeypatch, case, device)


@pytest.mark.parametrize("name,args,expected", [
    ("android_tap", {"x": 1, "y": 2}, {"x": 1, "y": 2}),
    ("android_long_press", {"x": 1, "y": 2}, {"x": 1, "y": 2, "duration": 500}),
    ("android_tap_text", {"text": "synthetic"}, {"text": "synthetic", "exact": False}),
    ("android_type", {"text": ""}, {"text": "", "clearFirst": False}),
    ("android_swipe", {"direction": "up"}, {"direction": "up", "distance": "medium"}),
    ("android_drag", {"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4}, {"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4, "duration_ms": 500, "hold_ms": 0}),
    ("android_scroll", {"direction": "up"}, {"direction": "up"}),
    ("android_wait", {}, {"timeoutMs": 5000}),
])
def test_input_defaults_and_coordinate_variants(monkeypatch, name, args, expected):
    with FakeRelay() as relay:
        install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()}))
        assert json.loads(tools_input.HANDLERS[name](args))["success"] is True
        assert relay.requests[0]["body"] == expected


@pytest.mark.parametrize("name,args", [
    ("android_tap", {}), ("android_long_press", {}),
    ("android_long_press", {"node_id": "w0:1", "x": 1, "y": 2}),
    ("android_long_press", {"node_id": "w0:1", "duration": True}),
    ("android_long_press", {"node_id": "w0:1", "duration": 99}),
    ("android_drag", {"start_x": -1, "start_y": 2, "end_x": 3, "end_y": 4}),
    ("android_drag", {"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4, "duration": False}),
])
def test_invalid_inputs_fail_without_transport(monkeypatch, name, args, caplog):
    monkeypatch.setattr(tools_input, "call", lambda *args: pytest.fail("Invalid input reached transport"))
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        assert "error" in json.loads(tools_input.HANDLERS[name](args))
    assert any(record.exc_info for record in caplog.records)


@pytest.mark.parametrize("duration,expected", [(1, 100), (9000, 3000)])
def test_drag_clamping_is_visible(monkeypatch, duration, expected, caplog):
    calls = []
    monkeypatch.setattr(tools_input, "call", lambda method, path, args, body: calls.append(body) or '{}')
    with caplog.at_level(logging.WARNING, logger="atomic_bridge"):
        tools_input.android_drag({"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4, "duration": duration})
    assert calls[0]["duration_ms"] == expected
    assert "clamped" in caplog.text


@pytest.mark.parametrize("hold_ms", [True, False, 1.5, "600", None])
def test_invalid_drag_hold_is_a_tool_input_error_before_transport(monkeypatch, caplog, hold_ms):
    monkeypatch.setattr(tools_input, "call", lambda *args: pytest.fail("Invalid hold reached transport"))
    args = {"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4, "hold_ms": hold_ms}
    with pytest.raises(ToolInputError, match="hold_ms must be an integer in milliseconds"):
        tools_input.android_drag.__wrapped__(args)
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        assert json.loads(tools_input.android_drag(args)) == {"error": "hold_ms must be an integer in milliseconds"}
    assert any(record.exc_info for record in caplog.records)


@pytest.mark.parametrize("hold_ms,expected,warned", [(0, 0, False), (600, 600, False), (3000, 3000, False), (-1, 0, True), (9000, 3000, True)])
def test_drag_hold_range_and_warning(monkeypatch, caplog, hold_ms, expected, warned):
    calls = []
    monkeypatch.setattr(tools_input, "call", lambda method, path, args, body: calls.append(body) or '{}')
    with caplog.at_level(logging.WARNING, logger="atomic_bridge"):
        tools_input.android_drag({"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4, "hold_ms": hold_ms})
    assert calls == [{"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4, "duration_ms": 500, "hold_ms": expected}]
    warnings = [record for record in caplog.records if record.levelno == logging.WARNING]
    assert [record.getMessage() for record in warnings] == (["component=tools operation=android_drag hold_ms clamped to supported range"] if warned else [])
