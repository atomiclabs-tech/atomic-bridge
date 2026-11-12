"""Independent wire examples for the frozen upstream tool inventory."""

import base64
import io
import json
from urllib.parse import parse_qs, urlsplit

from atomic_bridge import tool_support, tools_phone, tools_screen, tools_setup
from atomic_bridge.client import RelayClient
from atomic_bridge.registry import TOOL_ENTRIES
from fake_relay import admin_environment, FakeRelay

PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
TOKEN = "synthetic_media_123456"
MARKER = "MEDIA:hermes-relay://" + TOKEN

# Each body below is written independently of the implementation's mappings.
CASES = [
    ("android_ping", {}, "GET", "/ping", {}),
    ("android_read_screen", {"include_bounds": True}, "GET", "/screen", {"include_bounds": "true"}),
    ("android_find_nodes", {"text": "synthetic", "class_name": "Button", "clickable": False, "limit": 3}, "POST", "/find_nodes", {"text": "synthetic", "class_name": "Button", "clickable": False, "limit": 3}),
    ("android_tap", {"node_id": "w0:1"}, "POST", "/tap", {"nodeId": "w0:1"}),
    ("android_tap_text", {"text": "synthetic", "exact": True}, "POST", "/tap_text", {"text": "synthetic", "exact": True}),
    ("android_long_press", {"node_id": "w0:2", "duration": 600}, "POST", "/long_press", {"node_id": "w0:2", "duration": 600}),
    ("android_type", {"text": "synthetic", "clear_first": True}, "POST", "/type", {"text": "synthetic", "clearFirst": True}),
    ("android_swipe", {"direction": "left", "distance": "long"}, "POST", "/swipe", {"direction": "left", "distance": "long"}),
    ("android_drag", {"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4, "duration": 800}, "POST", "/drag", {"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4, "duration_ms": 800, "hold_ms": 0}),
    ("android_drag", {"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4, "duration": 800, "hold_ms": 600}, "POST", "/drag", {"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4, "duration_ms": 800, "hold_ms": 600}),
    ("android_open_app", {"package": "synthetic.app"}, "POST", "/open_app", {"package": "synthetic.app"}),
    ("android_press_key", {"key": "home"}, "POST", "/press_key", {"key": "home"}),
    ("android_screenshot", {}, "GET", "/screenshot", {}),
    ("android_scroll", {"direction": "down", "node_id": "w0:3"}, "POST", "/scroll", {"direction": "down", "nodeId": "w0:3"}),
    ("android_wait", {"text": "synthetic", "class_name": "Button", "timeout_ms": 17}, "POST", "/wait", {"text": "synthetic", "className": "Button", "timeoutMs": 17}),
    ("android_get_apps", {}, "GET", "/apps", {}),
    ("android_current_app", {}, "GET", "/current_app", {}),
    ("android_media", {"action": "next"}, "POST", "/media", {"action": "next"}),
    ("android_describe_node", {"node_id": "w0:4"}, "POST", "/describe_node", {"nodeId": "w0:4"}),
    ("android_setup", {}, "GET", "/health", {}),
    ("android_macro", {"steps": [{"tool": "android_ping"}], "pace_ms": 0}, "GET", "/ping", {}),
    ("android_clipboard_read", {}, "GET", "/clipboard", {}),
    ("android_clipboard_write", {"text": "synthetic"}, "POST", "/clipboard", {"text": "synthetic"}),
    ("android_screen_hash", {}, "GET", "/screen_hash", {}),
    ("android_diff_screen", {"previous_hash": "synthetic-hash"}, "POST", "/diff_screen", {"previous_hash": "synthetic-hash"}),
    ("android_send_intent", {"action": "synthetic.ACTION", "data": "synthetic:data", "package": "synthetic.app", "component": "synthetic.app/Activity", "extras": {"key": "synthetic"}, "category": "synthetic.CATEGORY"}, "POST", "/send_intent", {"action": "synthetic.ACTION", "data": "synthetic:data", "package": "synthetic.app", "component": "synthetic.app/Activity", "extras": {"key": "synthetic"}, "category": "synthetic.CATEGORY"}),
    ("android_broadcast", {"action": "synthetic.ACTION", "data": "synthetic:data", "package": "synthetic.app", "extras": {"key": "synthetic"}}, "POST", "/broadcast", {"action": "synthetic.ACTION", "data": "synthetic:data", "package": "synthetic.app", "extras": {"key": "synthetic"}}),
    ("android_events", {"limit": 7, "since": 123}, "GET", "/events", {"limit": "7", "since": "123"}),
    ("android_event_stream", {"enabled": False}, "POST", "/events/stream", {"enabled": False}),
    ("android_location", {}, "GET", "/location", {}),
    ("android_search_contacts", {"query": "synthetic", "limit": 4}, "POST", "/search_contacts", {"query": "synthetic", "limit": 4}),
    ("android_call", {"number": "synthetic-number"}, "POST", "/call", {"number": "synthetic-number"}),
    ("android_send_sms", {"to": "synthetic-number", "body": "synthetic"}, "POST", "/send_sms", {"to": "synthetic-number", "body": "synthetic"}),
    ("android_return_to_hermes", {}, "POST", "/return_to_hermes", {}),
    ("android_share_media", {"media_token": TOKEN, "text": "synthetic", "title": "synthetic", "package": "synthetic.app"}, "POST", "/share_media", {"attachments": [{"media": MARKER}], "text": "synthetic", "title": "synthetic", "package": "synthetic.app"}),
    ("android_send_mms", {"to": "synthetic-number", "body": "synthetic", "media_token": TOKEN, "package": "synthetic.app"}, "POST", "/send_mms", {"to": "synthetic-number", "body": "synthetic", "attachments": [{"media": MARKER}], "package": "synthetic.app"}),
]


def install_client(monkeypatch, client):
    for module in (tool_support, tools_screen, tools_phone, tools_setup):
        monkeypatch.setattr(module, "RelayClient", lambda: client)


def assert_wire_case(monkeypatch, case, device):
    name, arguments, method, path, expected = case
    arguments = dict(arguments)
    expected = dict(expected)
    if device:
        arguments["device"] = "synthetic-device"
        expected["device"] = "synthetic-device"
    with FakeRelay() as relay:
        install_client(monkeypatch, RelayClient(environ={
            "ANDROID_BRIDGE_URL": relay.url, **admin_environment(),
        }))
        handler = next(entry.handler for entry in TOOL_ENTRIES if entry.name == name)
        result = json.loads(handler(arguments))
        assert result == {"success": True}
        assert len(relay.requests) == 1
        request = relay.requests[0]
        assert (request["method"], request["path"]) == (method, path)
        assert (request["query"] if method == "GET" else request["body"]) == expected
        assert request["headers"]["Authorization"] == "Bearer synthetic-auth"


class Response(io.BytesIO):
    def __init__(self, value, *, status=200, content_type="application/json"):
        super().__init__(json.dumps(value).encode() if isinstance(value, dict) else value)
        self.status = status
        self.headers = {"Content-Type": content_type}


class MediaOpener:
    def __init__(self, *, fetch_status=200, image=PNG, mime="image/png", command=None, sensitive=None):
        self.requests = []
        self.fetch_status, self.image, self.mime = fetch_status, image, mime
        self.command = {"success": True, "media": MARKER} if command is None else command
        self.sensitive = {"ok": True} if sensitive is None else sensitive

    def open(self, request, *, timeout):
        parts = urlsplit(request.full_url)
        self.requests.append({
            "method": request.method, "path": parts.path,
            "query": {key: values[0] for key, values in parse_qs(parts.query).items()},
            "body": json.loads(request.data) if request.data else None,
            "headers": dict(request.header_items()), "timeout": timeout,
        })
        if parts.path == "/screenshot":
            return Response(self.command)
        if parts.path.endswith("/sensitive"):
            return Response(self.sensitive)
        return Response(self.image, status=self.fetch_status, content_type=self.mime)
