# no_parallel: opens a loopback test server on port 0.
import json
import logging
from pathlib import Path

import pytest

from fake_relay import FakeRelay, FakeResponse, admin_environment

from atomic_bridge import registry, tools_audio, tools_files, tools_notifications
from atomic_bridge.client import RelayClient
from atomic_bridge.filestream import MAX_FILE_BYTES
from new_tool_cases import DIGEST, MARKER, PAYLOAD, TOKEN, NewToolRelay, TracedClient, install_client

NAMES = ["android_notifications_recent", "android_wait_notification", "android_list_files",
         "android_pull_file", "android_push_file", "android_call_audio_probe"]


def test_file_list_optional_path_limit_and_device(monkeypatch):
    with NewToolRelay() as relay:
        install_client(monkeypatch, TracedClient(relay))
        result = json.loads(tools_files.android_list_files({"path": "Download", "limit": 3, "device": "synthetic"}))
        assert result == {"success": True, "entries": [], "count": 0, "truncated": False}
        assert relay.requests[0]["query"] == {"path": "Download", "limit": "3", "device": "synthetic"}
        tools_files.android_list_files({})
        assert relay.requests[1]["query"] == {"limit": "200"}


def test_pull_real_stream_wire_and_long_budgets(monkeypatch, tmp_path):
    with NewToolRelay() as relay:
        client = TracedClient(relay)
        install_client(monkeypatch, client)
        result = json.loads(tools_files.android_pull_file({"path": "Download/synthetic.bin", "dest": str(tmp_path), "device": "synthetic"}))
        assert Path(result["path"]).read_bytes() == PAYLOAD
        assert (result["size"], result["sha256"]) == (len(PAYLOAD), DIGEST)
        assert client.calls == [("POST", "/files/pull", {"path": "Download/synthetic.bin", "device": "synthetic"}, 300), ("DOWNLOAD", "/media/" + TOKEN, MAX_FILE_BYTES, 300)]
        assert [(request["method"], request["path"]) for request in relay.requests] == [("POST", "/files/pull"), ("GET", "/media/" + TOKEN)]
        assert relay.requests[0]["body"] == {"path": "Download/synthetic.bin", "device": "synthetic"}
        assert "dest" not in relay.requests[0]["body"]


@pytest.mark.parametrize("overrides,expected_name,expected_extra", [({}, "local.bin", {}), ({"name": "phone.bin", "dir": "Pictures", "overwrite": True}, "phone.bin", {"dir": "Pictures"})])
def test_push_registration_and_phone_body_with_verified_hash(monkeypatch, tmp_path, overrides, expected_name, expected_extra):
    source = tmp_path / "local.bin"
    source.write_bytes(PAYLOAD)
    with NewToolRelay() as relay:
        client = TracedClient(relay)
        install_client(monkeypatch, client)
        result = json.loads(tools_files.android_push_file({"path": str(source), "device": "synthetic", **overrides}))
        assert result["success"] is True and result["scanned"] is True
        registration, push = relay.requests
        assert (registration["method"], registration["path"], registration["body"]) == ("POST", "/media/register", {"path": str(source), "content_type": "application/octet-stream", "file_name": "local.bin"})
        assert registration["headers"]["Authorization"] == "Bearer " + relay.expected_secret
        assert push["path"] == "/files/push" and push["body"] == {"media": MARKER, "name": expected_name, "size": len(PAYLOAD), "sha256": DIGEST, "overwrite": overrides.get("overwrite", False), "device": "synthetic", **expected_extra}
        assert [call[3] for call in client.calls] == [300, 300]
        assert source.read_bytes() == PAYLOAD


@pytest.mark.parametrize("kind", ["missing", "oversized", "directory"])
def test_push_rejects_unusable_local_file_before_any_request(monkeypatch, tmp_path, kind, caplog):
    source = tmp_path / "synthetic-source.bin"
    if kind == "oversized":
        with source.open("wb") as stream:
            stream.truncate(MAX_FILE_BYTES + 1)
    elif kind == "directory":
        source.mkdir()
    monkeypatch.setattr(tools_files, "RelayClient", lambda: pytest.fail("Rejected file reached transport"))
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = json.loads(tools_files.android_push_file({"path": str(source)}))
    assert "error" in result and result["stage"] == "read"
    leaked = str(source) in caplog.text
    assert leaked is False


def test_push_registration_checksum_mismatch_stops_before_phone(monkeypatch, tmp_path):
    source = tmp_path / "synthetic.bin"
    source.write_bytes(PAYLOAD)
    with NewToolRelay() as relay:
        client = TracedClient(relay)
        request = client.request

        def mismatch(*args, **kwargs):
            response = request(*args, **kwargs)
            response.value["sha256"] = "0" * 64
            return response

        monkeypatch.setattr(client, "request", mismatch)
        install_client(monkeypatch, client)
        result = json.loads(tools_files.android_push_file({"path": str(source)}))
        assert result["stage"] == "register" and "SHA-256" in result["error"]
        assert [item["path"] for item in relay.requests] == ["/media/register"]


@pytest.mark.parametrize("status", [401, 403, 500])
def test_push_register_refusal_adds_allowed_roots_hint_only_for_403(monkeypatch, tmp_path, status):
    source = tmp_path / "synthetic.bin"
    source.write_bytes(PAYLOAD)
    with FakeRelay(lambda request: FakeResponse(status=status, value={"error": "media path is outside allowed roots"})) as relay:
        install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()}))
        result = json.loads(tools_files.android_push_file({"path": str(source)}))
        expected = {"error": f"HTTP {status}: media path is outside allowed roots", "status": status, "stage": "register"}
        if status == 403:
            expected["hint"] = "The relay registers local files only under its allowed roots (ATOMIC_RELAY_MEDIA_ALLOWED_ROOTS, default ~/.hermes/workspace). Copy the file under one of them and retry."
        assert result == expected
        assert [row["path"] for row in relay.requests] == ["/media/register"]


def test_push_stage_403_does_not_add_a_registration_hint(monkeypatch, tmp_path):
    source = tmp_path / "synthetic.bin"
    source.write_bytes(PAYLOAD)
    def respond(request):
        if request["path"] == "/media/register":
            return FakeResponse(value={"ok": True, "token": TOKEN, "size": len(PAYLOAD), "sha256": DIGEST})
        return FakeResponse(status=403, value={"error": "synthetic push refused"})
    with FakeRelay(respond) as relay:
        install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()}))
        result = json.loads(tools_files.android_push_file({"path": str(source)}))
        assert result == {"error": "HTTP 403: synthetic push refused", "status": 403}
        assert [row["path"] for row in relay.requests] == ["/media/register", "/files/push"]


@pytest.mark.parametrize("outcome", ["success", "phone_failure", "http_error", "transport_error"])
@pytest.mark.parametrize("name", NAMES)
def test_all_six_new_handlers_return_json_for_every_outcome(monkeypatch, tmp_path, caplog, name, outcome):
    source = tmp_path / "local.bin"
    source.write_bytes(PAYLOAD)
    arguments = {
        "android_notifications_recent": {}, "android_wait_notification": {}, "android_list_files": {},
        "android_pull_file": {"path": "Download/synthetic.bin", "dest": str(tmp_path / "result.bin")},
        "android_push_file": {"path": str(source)}, "android_call_audio_probe": {},
    }[name]
    private = "synthetic-private-" + "transport"

    class Refused:
        def open(self, *args, **kwargs):
            raise ConnectionRefusedError(private)

    with NewToolRelay(outcome) as relay:
        client = TracedClient(relay) if outcome != "transport_error" else RelayClient(
            environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()}, opener=Refused(),
        )
        install_client(monkeypatch, client)
        handler = next(entry.handler for entry in registry.TOOL_ENTRIES if entry.name == name)
        with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
            raw = handler(arguments, session_id="synthetic", task_id="synthetic")
        assert isinstance(raw, str)
        result = json.loads(raw)
        if outcome == "success":
            assert "error" not in result and result.get("success") is not False
        else:
            assert "error" in result and result.get("success") is not True
        if outcome == "http_error":
            assert result["status"] == 503
        leaked = private in raw or private in caplog.text
        assert leaked is False


def test_six_new_registry_entries_have_real_handlers():
    expected = {**tools_files.HANDLERS, **tools_notifications.HANDLERS, **tools_audio.HANDLERS}
    assert set(expected) == set(NAMES)
    actual = {entry.name: entry.handler for entry in registry.TOOL_ENTRIES if entry.name in NAMES}
    assert actual == expected
