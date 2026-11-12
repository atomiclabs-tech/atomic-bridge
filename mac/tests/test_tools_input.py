# no_parallel: opens a loopback test server on port 0.
import json
import logging

import pytest

from atomic_bridge import tools_input
from atomic_bridge.client import RelayClient
from atomic_bridge.tool_support import ToolInputError
from fake_relay import admin_environment, FakeRelay
from tool_cases import CASES, assert_wire_case, install_client


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
