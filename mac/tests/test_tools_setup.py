# no_parallel: opens a loopback test server on port 0.
import json
import logging
from pathlib import Path

import pytest

from atomic_bridge import registry, tools_setup
from atomic_bridge.client import RelayClient, RelayResponse
from fake_relay import admin_environment, FakeRelay, FakeResponse
from tool_cases import install_client


@pytest.mark.parametrize("status", [200, 400])
@pytest.mark.parametrize("numeric", [False, True])
def test_setup_redacts_supplied_code_from_success_and_error_diagnostics(monkeypatch, caplog, status, numeric):
    points = (49, 50, 51, 52, 53, 54) if numeric else (65, 66, 67, 49, 50, 51)
    supplied_code = "".join(chr(point) for point in points)
    calls = []
    registration = {
        "ok": status == 200, "code": supplied_code, "count": 2,
        "metadata": {"messages": ["registered " + supplied_code, {"code": supplied_code}], "unchanged": None},
    }
    if status != 200:
        registration["error"] = "Registration rejected " + supplied_code

    class Client:
        def request(self, method, path, body, *, timeout):
            calls.append((method, path, body, timeout))
            if path == "/pairing/register":
                return RelayResponse(status, registration)
            return RelayResponse(200, {"ok": True, "message": "diagnostic " + supplied_code})

    install_client(monkeypatch, Client())
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        raw = tools_setup.android_setup({"pairing_code": supplied_code})
    disclosed = supplied_code in raw or supplied_code in caplog.text
    assert disclosed is False
    result = json.loads(raw)
    assert result["success"] is (status == 200)
    diagnostic = result["diagnostics"]["/pairing/register"]
    assert diagnostic["status"] == status
    expected = {
        "ok": status == 200, "code": "[redacted]", "count": 2,
        "metadata": {"messages": ["registered [redacted]", {"code": "[redacted]"}], "unchanged": None},
    }
    if status != 200:
        expected["error"] = "Registration rejected [redacted]"
    assert diagnostic["result"] == expected
    assert all(result["diagnostics"][path] == {"status": 200, "result": {"ok": True, "message": "diagnostic [redacted]"}} for path in ("/health", "/bridge/status", "/ping"))
    wire_unchanged = calls == [("POST", "/pairing/register", {"code": supplied_code}, 2)] + [("GET", path, None, 2) for path in ("/health", "/bridge/status", "/ping")]
    assert wire_unchanged is True
    original_unchanged = registration["code"] == supplied_code
    assert original_unchanged is True


def test_setup_registers_code_and_probes_loopback_with_admin_without_writes(monkeypatch):
    def respond(request):
        if request["path"] == "/ping":
            return FakeResponse(status=401, value={"error": "synthetic auth required"})
        return FakeResponse(value={"ok": True})

    with FakeRelay(respond) as relay:
        client = RelayClient(environ=admin_environment(ANDROID_BRIDGE_URL=relay.url))
        install_client(monkeypatch, client)
        with monkeypatch.context() as guard:
            guard.setattr(Path, "write_text", lambda *args, **kwargs: pytest.fail("Setup wrote a file"))
            result = json.loads(tools_setup.android_setup({"pairing_code": "0O1IAZ", "bridge_session_token": "synthetic-ignored-token"}))
        assert [(request["method"], request["path"]) for request in relay.requests] == [
            ("POST", "/pairing/register"), ("GET", "/health"), ("GET", "/bridge/status"), ("GET", "/ping"),
        ]
        assert relay.requests[0]["body"] == {"code": "0O1IAZ"}
        assert all(request["headers"]["Authorization"] == "Bearer " + relay.expected_secret for request in relay.requests)
    assert result["success"] is False
    assert result["diagnostics"]["/ping"]["status"] == 401
    assert "401" in result["diagnostics"]["/ping"]["result"]["error"]
    assert result["note"] == "Supplied session token ignored; Atomic authenticates with its admin credential."
    assert "synthetic-ignored-token" not in json.dumps(result)


def test_setup_rejects_nonloopback_configuration_before_any_network(monkeypatch):
    class NoNetwork:
        def open(self, *args, **kwargs):
            pytest.fail("Nonloopback setup opened a URL")

    install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": "https://outside.example"}, opener=NoNetwork()))
    value = json.loads(tools_setup.android_setup({"pairing_code": "0O1IAZ"}))
    assert value["success"] is False
    assert set(value["diagnostics"]) == {"/pairing/register", "/health", "/bridge/status", "/ping"}
    assert all(item["status"] is None and "error" in item["result"] for item in value["diagnostics"].values())


def test_setup_continues_diagnostics_after_unexpected_failure(monkeypatch, caplog):
    calls = []
    private = "synthetic-private-" + "diagnostic"

    class Client:
        def request(self, method, path, *args, **kwargs):
            calls.append(path)
            if path == "/health":
                raise RuntimeError(private)
            return RelayResponse(200, {"ok": True})

    install_client(monkeypatch, Client())
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        value = json.loads(tools_setup.android_setup({"pairing_code": "bad"}))
    assert calls == ["/health", "/bridge/status", "/ping"]
    assert "Pairing code" in value["diagnostics"]["/pairing/register"]["result"]["error"]
    assert "synthetic-private" not in caplog.text
    assert len(value["diagnostics"]) == 4 and value["success"] is False


def _entries(monkeypatch, handlers):
    monkeypatch.setattr(registry, "TOOL_ENTRIES", tuple(registry.ToolEntry(name, {}, function) for name, function in handlers.items()))


@pytest.mark.parametrize("failure", [{"success": False, "code": "unsupported_command"}, {"error": "synthetic"}, {"status": "error", "message": "synthetic"}])
def test_macro_stops_on_first_failure(monkeypatch, failure):
    calls = []

    def handler(args):
        calls.append(args)
        return json.dumps(failure if len(calls) == 2 else {"success": True})

    _entries(monkeypatch, {"android_ping": handler})
    result = json.loads(tools_setup.android_macro({"steps": [{"tool": "android_ping"}] * 3, "pace_ms": 0, "device": "synthetic"}))
    assert result["success"] is False and result["completed"] == 1
    assert len(calls) == 2 and calls == [{"device": "synthetic"}] * 2


def test_macro_reduces_screenshot_to_summary_and_paces_between_steps_only(monkeypatch):
    sleeps = []
    _entries(monkeypatch, {"android_screenshot": lambda args: {"_multimodal": True, "content": [{"image_url": "synthetic-private-data"}], "text_summary": "synthetic summary"}})
    monkeypatch.setattr(tools_setup.time, "sleep", sleeps.append)
    value = json.loads(tools_setup.android_macro({"steps": [{"tool": "android_screenshot"}] * 2}))
    assert value["success"] is True and value["completed"] == 2
    assert sleeps == [0.5]
    assert all(item["result"] == {"summary": "synthetic summary"} for item in value["results"])
    assert "synthetic-private" not in json.dumps(value)


@pytest.mark.parametrize("arguments", [{"steps": "bad"}, {"steps": [], "pace_ms": -1}, {"steps": [None]}, {"steps": [{"tool": "missing"}]}, {"steps": [{"tool": "android_ping", "args": []}]}])
def test_macro_malformed_inputs_are_errors(arguments):
    value = json.loads(tools_setup.android_macro(arguments))
    assert "error" in value
