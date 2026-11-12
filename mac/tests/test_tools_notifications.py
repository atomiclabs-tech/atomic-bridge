# no_parallel: opens a loopback test server on port 0.
import json
import logging

import pytest

from atomic_bridge import tools_notifications
from atomic_bridge.client import RelayResponse
from new_tool_cases import NewToolRelay, TracedClient, install_client


def test_recent_filters_and_minute_conversion_use_exact_wire(monkeypatch):
    monkeypatch.setattr(tools_notifications.time, "time", lambda: 1000)
    with NewToolRelay() as relay:
        install_client(monkeypatch, TracedClient(relay))
        raw = tools_notifications.android_notifications_recent({"limit": 3, "package": "synthetic.app", "text": "synthetic", "since_minutes": 1.5, "device": "synthetic-device"})
        assert isinstance(raw, str)
        assert json.loads(raw) == {"status": "ok", "count": 1, "notifications": [{"package": "synthetic.app", "text": "synthetic"}]}
        request = relay.requests[0]
        assert (request["method"], request["path"], request["query"]) == ("GET", "/notifications/recent", {"limit": "3", "package": "synthetic.app", "text": "synthetic", "since": "910000", "device": "synthetic-device"})


def test_recent_defaults_do_not_add_absent_filters(monkeypatch):
    with NewToolRelay() as relay:
        install_client(monkeypatch, TracedClient(relay))
        tools_notifications.android_notifications_recent({})
        assert relay.requests[0]["query"] == {"limit": "20"}


@pytest.mark.parametrize("requested,expected", [(0, 1), (999, 120), (60, 60)])
def test_wait_clamp_and_http_budget(monkeypatch, caplog, requested, expected):
    with NewToolRelay() as relay:
        client = TracedClient(relay)
        install_client(monkeypatch, client)
        with caplog.at_level(logging.WARNING, logger="atomic_bridge"):
            raw = tools_notifications.android_wait_notification({"timeout_s": requested, "package": "synthetic.app", "text": "synthetic", "device": "synthetic"})
        assert json.loads(raw) == {"matched": False, "waited_s": 60}
        assert client.calls == [("GET", "/notifications/wait", {"timeout_s": expected, "package": "synthetic.app", "text": "synthetic", "device": "synthetic"}, expected + 10)]
        assert relay.requests[0]["query"] == {"timeout_s": str(expected), "package": "synthetic.app", "text": "synthetic", "device": "synthetic"}
        assert ("clamped" in caplog.text) is (requested != expected)


def test_wait_returns_matching_entry_unchanged(monkeypatch):
    entry = {"package": "synthetic.app", "key": "synthetic-key"}

    class Client:
        def request(self, *args, **kwargs):
            return RelayResponse(200, {"ok": True, "matched": True, "notification": entry})

    install_client(monkeypatch, Client())
    assert json.loads(tools_notifications.android_wait_notification({})) == entry


@pytest.mark.parametrize("handler", [tools_notifications.android_notifications_recent, tools_notifications.android_wait_notification])
def test_actual_server_error_is_not_replaced_by_a_success(monkeypatch, handler):
    value = {"ok": False, "error": "synthetic failed notification request", "code": "synthetic_error"}

    class Client:
        def request(self, *args, **kwargs):
            return RelayResponse(200, value)

    install_client(monkeypatch, Client())
    assert json.loads(handler({})) == value


@pytest.mark.parametrize("handler,value", [
    (tools_notifications.android_notifications_recent, {"ok": True, "count": 1, "notifications": []}),
    (tools_notifications.android_wait_notification, {"ok": True, "matched": True}),
    (tools_notifications.android_wait_notification, {"ok": True, "matched": False}),
])
def test_malformed_server_success_is_a_visible_error(monkeypatch, handler, value):
    class Client:
        def request(self, *args, **kwargs):
            return RelayResponse(200, value)

    install_client(monkeypatch, Client())
    assert "malformed" in json.loads(handler({}))["error"]


@pytest.mark.parametrize("function,args", [
    (tools_notifications.android_notifications_recent, {"since_minutes": -1}),
    (tools_notifications.android_notifications_recent, {"since_minutes": float("inf")}),
    (tools_notifications.android_notifications_recent, {"limit": True}),
    (tools_notifications.android_wait_notification, {"timeout_s": "60"}),
])
def test_bad_notification_inputs_fail_before_network(monkeypatch, function, args):
    monkeypatch.setattr(tools_notifications, "RelayClient", lambda: pytest.fail("Invalid input reached transport"))
    assert "error" in json.loads(function(args))
