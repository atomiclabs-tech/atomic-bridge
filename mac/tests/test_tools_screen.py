# no_parallel: opens a loopback test server on port 0.
import base64
import json
import logging

import pytest

from atomic_bridge import tools_screen
from atomic_bridge.client import RelayClient
from fake_relay import admin_environment, FakeRelay
from tool_cases import CASES, MARKER, PNG, TOKEN, MediaOpener, assert_wire_case, install_client


@pytest.mark.parametrize("device", [False, True])
@pytest.mark.parametrize("case", [case for case in CASES if case[0] in tools_screen.HANDLERS and case[0] != "android_screenshot"], ids=lambda case: case[0])
def test_screen_wire_matches_upstream(monkeypatch, case, device):
    assert_wire_case(monkeypatch, case, device)


def test_screen_default_bodies_preserve_false_and_absent_filters(monkeypatch):
    with FakeRelay() as relay:
        install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()}))
        tools_screen.android_read_screen({})
        tools_screen.android_find_nodes({})
        assert relay.requests[0]["query"] == {"include_bounds": "false"}
        assert relay.requests[1]["body"] == {"limit": 20}


@pytest.mark.parametrize("sensitive", [False, True])
def test_screenshot_envelope_download_wire_and_sensitive_marking(monkeypatch, sensitive):
    opener = MediaOpener()
    install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": "http://127.0.0.1", **admin_environment()}, opener=opener))
    result = tools_screen.android_screenshot({"device": "synthetic-device", "sensitive": sensitive})
    assert isinstance(result, dict) and result["_multimodal"] is True
    image_url = result["content"][1]["image_url"]["url"]
    assert image_url.startswith("data:image/png;base64,")
    assert base64.b64decode(image_url.split(",", 1)[1]) == PNG
    assert MARKER in result["text_summary"]
    assert f"1x1, {len(PNG)} bytes" in result["text_summary"]
    assert result["content"][0]["text"] == result["text_summary"]
    assert [(item["method"], item["path"]) for item in opener.requests[:2]] == [("GET", "/screenshot"), ("GET", "/media/" + TOKEN)]
    assert opener.requests[0]["query"] == {"device": "synthetic-device"}
    assert opener.requests[1]["headers"]["Authorization"] == "Bearer synthetic-auth"
    if sensitive:
        mark = opener.requests[2]
        assert (mark["method"], mark["path"], mark["body"]) == ("POST", f"/media/{TOKEN}/sensitive", {})
        assert mark["headers"]["Authorization"] == "Bearer synthetic-auth"
        assert "Sensitive screen" in result["text_summary"]
    else:
        assert len(opener.requests) == 2


@pytest.mark.parametrize("opener,stage,cause", [
    (MediaOpener(command={"success": False, "code": "capture_failed", "error": "synthetic capture cause"}), "command", "synthetic capture cause"),
    (MediaOpener(fetch_status=410), "fetch", "410"),
    (MediaOpener(mime="text/plain"), "fetch", "content type"),
    (MediaOpener(image=b"synthetic invalid image"), "decode", "PNG or JPEG"),
    (MediaOpener(sensitive={"ok": False, "error": "synthetic mark failure"}), "command", "synthetic mark failure"),
])
def test_screenshot_failures_are_json_with_actual_cause(monkeypatch, opener, stage, cause, caplog):
    install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": "http://127.0.0.1", **admin_environment()}, opener=opener))
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        raw = tools_screen.android_screenshot({"sensitive": True})
    assert isinstance(raw, str)
    value = json.loads(raw)
    assert value["stage"] == stage and cause in value["error"]
    assert "synthetic capture cause" not in caplog.text
    assert any(record.exc_info for record in caplog.records)
    if stage == "command" and "capture" in cause:
        assert value["code"] == "capture_failed" and len(opener.requests) == 1


def test_actual_eight_mib_limit_is_named_without_returning_partial_image(monkeypatch):
    class TooLarge(MediaOpener):
        def open(self, request, *, timeout):
            response = super().open(request, timeout=timeout)
            if request.full_url.endswith(TOKEN):
                response.headers["Content-Length"] = str(8 * 1024 * 1024 + 1)
            return response

    install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": "http://127.0.0.1", **admin_environment()}, opener=TooLarge()))
    value = json.loads(tools_screen.android_screenshot({}))
    assert value["stage"] == "fetch" and "8388608" in value["error"]
    assert "content" not in value


def test_unexpected_fetch_exception_keeps_stage_and_sanitizes_message(monkeypatch, caplog):
    private = "synthetic-private-" + "image-data"

    class Broken(MediaOpener):
        def open(self, request, *, timeout):
            if request.full_url.endswith(TOKEN):
                raise RuntimeError(private)
            return super().open(request, timeout=timeout)

    install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": "http://127.0.0.1", **admin_environment()}, opener=Broken()))
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        value = json.loads(tools_screen.android_screenshot({}))
    assert value == {"error": "Screenshot failed: RuntimeError", "stage": "fetch"}
    assert "synthetic-private" not in caplog.text
