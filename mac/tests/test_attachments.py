# no_parallel: opens a loopback test server on port 0.
import json

import pytest

from atomic_bridge import tools_phone
from atomic_bridge.attachments import normalize_attachments
from atomic_bridge.client import RelayClient
from atomic_bridge.tool_support import ToolInputError
from fake_relay import admin_environment, FakeRelay, FakeResponse
from tool_cases import MARKER, TOKEN, install_client


def test_host_registration_and_order_preserve_upstream_body(monkeypatch, tmp_path):
    path = tmp_path / "synthetic.txt"
    path.write_text("synthetic")

    def respond(request):
        return FakeResponse(value={"ok": True, "token": TOKEN} if request["path"] == "/media/register" else {"success": True})

    with FakeRelay(respond) as relay:
        install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()}))
        result = json.loads(tools_phone.android_share_media({"path": str(path), "media": TOKEN, "device": "synthetic-device"}))
        assert result == {"success": True}
        registration, share = relay.requests
        assert (registration["method"], registration["path"], registration["body"]) == (
            "POST", "/media/register", {"path": str(path), "content_type": "text/plain", "file_name": path.name, "sensitive": False, "owned_file": False},
        )
        assert registration["headers"]["Authorization"] == "Bearer " + relay.expected_secret
        assert share["body"] == {"attachments": [{"media": MARKER, "content_type": "text/plain", "file_name": path.name}, {"media": MARKER}], "device": "synthetic-device"}


def test_all_marker_forms_and_optional_aliases():
    values = normalize_attachments({
        "attachments": [{"media_token": TOKEN, "mime_type": "image/png", "filename": "synthetic.png"}],
        "media": MARKER, "media_token": "hermes-relay://" + TOKEN, "media_tokens": [TOKEN],
    }, None)
    assert values == [{"media": MARKER, "content_type": "image/png", "file_name": "synthetic.png"}] + [{"media": MARKER}] * 3


@pytest.mark.parametrize("args", [{"attachments": [None]}, {"attachments": [{}]}, {"paths": [3]}, {"path": "missing-synthetic-file"}, {"attachments": {}}])
def test_invalid_attachment_specs_are_not_silently_dropped(args):
    with pytest.raises(ToolInputError):
        normalize_attachments(args, None)


def test_registration_failure_stops_before_sharing(monkeypatch, tmp_path):
    path = tmp_path / "synthetic.txt"
    path.write_text("synthetic")
    with FakeRelay(lambda request: FakeResponse(status=503, value={"error": "synthetic"})) as relay:
        install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()}))
        result = json.loads(tools_phone.android_share_media({"path": str(path)}))
        assert "503" in result["error"]
        assert [request["path"] for request in relay.requests] == ["/media/register"]
