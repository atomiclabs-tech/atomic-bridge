# no_parallel: opens a loopback test server on port 0.
import logging

import pytest

from atomic_bridge import checks
from atomic_bridge.client import RelayClient, RelayResponse
from fake_relay import admin_environment, FakeRelay, FakeResponse


@pytest.mark.parametrize(("status", "value", "expected"), [
    (200, {"bridge": {"device_control_supported": False}, "phone_connected": True}, False),
    (200, {"phone_connected": True}, True),
    (200, {"phone_connected": False, "device_id": "synthetic-device"}, True),
    (200, {"phone_connected": False, "device": {"device_id": "synthetic-device"}}, True),
    (200, {"phone_connected": False}, True),
    (503, {"error": "not connected"}, True),
    (401, {"error": "not authorized"}, True),
])
def test_availability_uses_only_status_probe(monkeypatch, status, value, expected):
    calls = []

    class Client:
        def request(self, method, path, *, timeout):
            calls.append((method, path, timeout))
            return RelayResponse(status, value)

    monkeypatch.setattr(checks, "RelayClient", Client)
    assert checks.check_fn() is expected
    assert calls == [("GET", "/bridge/status", 2)]


def test_real_status_probe_keeps_known_disconnected_phone(monkeypatch):
    with FakeRelay(lambda request: FakeResponse(value={"phone_connected": False, "device_id": "synthetic"})) as relay:
        monkeypatch.setattr(checks, "RelayClient", lambda: RelayClient(environ={
            "ANDROID_BRIDGE_URL": relay.url, **admin_environment(),
        }))
        assert checks.check_fn() is True
        assert [request["path"] for request in relay.requests] == ["/bridge/status"]


def test_connection_refused_keeps_tools_visible(monkeypatch, caplog):
    class Refused:
        def open(self, request, *, timeout):
            raise ConnectionRefusedError("synthetic refusal")

    monkeypatch.setattr(checks, "RelayClient", lambda: RelayClient(
        environ={"ANDROID_BRIDGE_URL": "http://127.0.0.1", **admin_environment()},
        opener=Refused(),
    ))
    with caplog.at_level(logging.WARNING, logger="atomic_bridge"):
        assert checks.check_fn() is True
    assert any(record.exc_info for record in caplog.records if record.levelno == logging.ERROR)
    assert any(record.levelno == logging.WARNING for record in caplog.records)


def test_unexpected_transport_exception_never_propagates_or_logs_private_message(monkeypatch, caplog):
    private_message = "synthetic-private-" + "value"

    class Raising:
        def request(self, *args, **kwargs):
            raise RuntimeError(private_message)

    monkeypatch.setattr(checks, "RelayClient", Raising)
    with caplog.at_level(logging.WARNING, logger="atomic_bridge"):
        assert checks.check_fn() is True
    assert private_message not in caplog.text
    assert any(record.exc_info for record in caplog.records if record.levelno == logging.ERROR)
