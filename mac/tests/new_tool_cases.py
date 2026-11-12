"""Synthetic notification, file and probe responses for new-tool tests."""

import hashlib
from pathlib import Path

from atomic_bridge import tools_audio, tools_files, tools_notifications
from atomic_bridge.client import RelayClient
from fake_relay import admin_environment, FakeRelay, FakeResponse

PAYLOAD = (b"\0synthetic-file\x01" * 9000)
DIGEST = hashlib.sha256(PAYLOAD).hexdigest()
TOKEN = "synthetic_file_token_1234"
MARKER = "MEDIA:hermes-relay://" + TOKEN
PHONE_FAILURE = {"success": False, "error": "synthetic failure", "code": "permission_denied", "permission": "android.permission.MANAGE_EXTERNAL_STORAGE"}


def install_client(monkeypatch, client):
    for module in (tools_files, tools_notifications, tools_audio):
        monkeypatch.setattr(module, "RelayClient", lambda: client)


def metadata(**changes):
    return {"success": True, "media": MARKER, "name": "synthetic.bin", "size": len(PAYLOAD),
            "sha256": DIGEST, "content_type": "application/octet-stream", **changes}


class NewToolRelay(FakeRelay):
    def __init__(self, outcome="success"):
        self.outcome = outcome
        super().__init__(self._respond)

    def _respond(self, request):
        if self.outcome == "phone_failure":
            return FakeResponse(value=PHONE_FAILURE)
        if self.outcome == "http_error":
            return FakeResponse(status=503, value={"error": "synthetic unavailable"})
        path = request["path"]
        if path == "/notifications/recent":
            return FakeResponse(value={"ok": True, "count": 1, "notifications": [{"package": "synthetic.app", "text": "synthetic"}]})
        if path == "/notifications/wait":
            return FakeResponse(value={"ok": True, "matched": False, "waited_s": 60})
        if path == "/files":
            return FakeResponse(value={"success": True, "entries": [], "count": 0, "truncated": False})
        if path == "/files/pull":
            return FakeResponse(value=metadata())
        if path == "/media/" + TOKEN:
            return FakeResponse(text=PAYLOAD.decode("utf-8"), headers={"Content-Type": "application/octet-stream", "Content-Length": str(len(PAYLOAD))})
        if path == "/media/register":
            data = Path(request["body"]["path"]).read_bytes()
            return FakeResponse(value={"ok": True, "token": TOKEN, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        if path == "/files/push":
            return FakeResponse(value={"success": True, "path": "Download/synthetic.bin", "size": request["body"]["size"], "sha256": request["body"]["sha256"], "scanned": True})
        if path == "/call_audio_probe":
            return FakeResponse(value={"success": True, "capture": [{"initialized": False, "bytes": 0, "error": "synthetic unsupported source"}], "injection": {"played": False}})
        raise AssertionError("Unexpected fake-relay route")


def client_for(relay):
    return RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()})


class TracedClient(RelayClient):
    def __init__(self, relay):
        super().__init__(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()})
        self.calls = []

    def request(self, method, path, args=None, *, timeout=None):
        self.calls.append((method, path, args, timeout))
        return super().request(method, path, args, timeout=timeout)

    def download(self, path, sink, *, max_bytes, timeout=None):
        self.calls.append(("DOWNLOAD", path, max_bytes, timeout))
        return super().download(path, sink, max_bytes=max_bytes, timeout=timeout)
