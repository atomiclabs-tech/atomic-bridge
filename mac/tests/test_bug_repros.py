# no_parallel: runs git or a subprocess for pinned or archived source proof.
"""Identical contract assertions against the new code and named broken artifacts.

Run the three strict xfails with --runxfail -k broken_baseline to retain actual
failing baseline XML. The archived availability patch is a separate passing
control; pristine upstream supplies the screenshot and original availability baselines.
"""

import json

import pytest

from atomic_bridge import checks, tools_screen
from atomic_bridge.client import RelayClient, RelayResponse
from baseline_sources import availability, pristine_screenshot
from fake_relay import admin_environment
from tool_cases import MediaOpener, install_client


def _assert_screenshot_error(value):
    # The documented host contract accepts strings or multimodal envelopes only.
    assert isinstance(value, str), "Screenshot failure must be a JSON string accepted by the host"
    result = json.loads(value)
    assert result["stage"] == "fetch" and "410" in result["error"]


def _new_status(monkeypatch, status, value):
    calls = []

    class Client:
        def request(self, method, path, *, timeout):
            calls.append((method, path, timeout))
            return RelayResponse(status, value)

    monkeypatch.setattr(checks, "RelayClient", Client)
    return checks.check_fn, calls


def test_screenshot_failure_new_returns_json_naming_fetch_cause(monkeypatch):
    install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": "http://127.0.0.1", **admin_environment()}, opener=MediaOpener(fetch_status=410)))
    _assert_screenshot_error(tools_screen.android_screenshot({}))


@pytest.mark.xfail(strict=True, raises=AssertionError, reason="Pristine screenshot failure is an unsupported plain dict")
def test_broken_baseline_pristine_screenshot_failure_contract(monkeypatch):
    _assert_screenshot_error(pristine_screenshot(monkeypatch)())


@pytest.mark.parametrize("value", [{"phone_connected": False, "device_id": "synthetic"}, {"phone_connected": False, "device": {"device_id": "synthetic"}}])
def test_new_known_disconnected_device_stays_visible(monkeypatch, value):
    function, calls = _new_status(monkeypatch, 200, value)
    assert function() is True
    assert calls == [("GET", "/bridge/status", 2)]
    assert all(text in checks.__doc__ for text in ("freezes", "/reload-mcp", "/new", "compaction"))


@pytest.mark.xfail(strict=True, raises=AssertionError, reason="Pristine da782a8 hides a disconnected known device")
def test_broken_baseline_pristine_known_device():
    function, _ = availability("pristine", value={"phone_connected": False, "device_id": "synthetic"})
    assert function() is True


@pytest.mark.parametrize("value", [{"phone_connected": False, "device_id": "synthetic"}, {"phone_connected": False, "device": {"device_id": "synthetic"}}])
def test_archived_patch_passes_known_device_fixture(value):
    function, calls = availability("archived", value=value)
    assert function() is True
    assert calls == [("GET", "/bridge/status", 2)]


def test_new_availability_never_probes_ping(monkeypatch):
    function, calls = _new_status(monkeypatch, 503, {"error": "synthetic outage"})
    assert function() is True
    assert calls == [("GET", "/bridge/status", 2)]


@pytest.mark.xfail(strict=True, raises=AssertionError, reason="Archived availability falls back to a phone ping after status503")
def test_broken_baseline_archived_availability_never_probes_ping():
    function, calls = availability("archived", status=503, value={"error": "synthetic outage"})
    function()
    assert calls == [("GET", "/bridge/status", 2)]
