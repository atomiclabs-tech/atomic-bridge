# no_parallel: opens a loopback test server on port 0.
import io
import json
import logging
import socket
from http.client import IncompleteRead, RemoteDisconnected
from pathlib import Path
from urllib.error import HTTPError, URLError

import pytest

from atomic_bridge.client import RelayClient
from fake_relay import FakeRelay, FakeResponse, admin_environment

MISSING_SECRET = "Admin secret file is missing or empty; run tools/install-mac.sh or set ATOMIC_BRIDGE_ADMIN_SECRET_FILE"


class _Environment(dict):
    def get(self, key, default=None):
        # Reject unexpected credential discovery before it can access any live path.
        assert key in {"ANDROID_BRIDGE_URL", "ANDROID_BRIDGE_TIMEOUT", "ATOMIC_BRIDGE_ADMIN_SECRET_FILE"}
        return super().get(key, default)


def _environment(relay=None, **values):
    return _Environment(admin_environment(**({"ANDROID_BRIDGE_URL": relay.url} if relay else {}), **values))


class _Response(io.BytesIO):
    status = 200


class _Opener:
    def __init__(self, response=b'{"success": true}', error=None):
        self.response, self.error, self.calls = response, error, []

    def open(self, request, *, timeout):
        self.calls.append((request, timeout))
        if self.error:
            raise self.error
        return _Response(self.response)


def test_attack11_get_post_and_registration_send_admin_bearer_without_mutation():
    with FakeRelay() as relay:
        client = RelayClient(environ=_environment(relay))
        args = {"device": " synthetic phone ", "limit": 3}
        assert json.loads(client.get("/events?since=1", args)) == {"success": True}
        assert json.loads(client.post("/tap", args)) == {"success": True}
        assert client.request("POST", "/media/register", {"path": "synthetic"}).status == 200
        get, post, registration = relay.requests
        assert get["query"] == {"since": "1", "device": "synthetic phone", "limit": "3"}
        assert post["body"] == {"device": "synthetic phone", "limit": 3}
        assert all(item["headers"]["Authorization"] == "Bearer " + relay.expected_secret for item in relay.requests)
        assert registration["path"] == "/media/register" and relay.rejected == 0
        assert args == {"device": " synthetic phone ", "limit": 3}


def test_secret_is_cached_until_auth_denial(tmp_path):
    path = tmp_path / "configured.secret"
    first, second = "synthetic-" + "first", "synthetic-" + "second"
    path.write_text(first)
    path.chmod(0o600)
    with FakeRelay(secret=first) as relay:
        client = RelayClient(environ=_environment(relay, ATOMIC_BRIDGE_ADMIN_SECRET_FILE=str(path)))
        assert client.request("GET", "/ping").status == 200
        path.write_text(second)
        assert client.request("GET", "/ping").status == 200
        assert all(item["headers"]["Authorization"] == "Bearer " + first for item in relay.requests)


@pytest.mark.parametrize("status", [401, 403])
@pytest.mark.parametrize("operation", ["request", "download"])
def test_auth_denial_refreshes_once_but_does_not_repeat_command(tmp_path, monkeypatch, status, operation):
    path = tmp_path / "admin.secret"
    first, second = "synthetic-" + "stale", "synthetic-" + "fresh"
    path.write_text(first)
    path.chmod(0o600)
    reads = []
    read_text = Path.read_text

    def read(source, *args, **kwargs):
        if source == path:
            reads.append(source)
        return read_text(source, *args, **kwargs)

    def respond(request):
        if len(relay.requests) == 1:
            path.write_text(second)
            relay.expected_secret = second
            return FakeResponse(status=status, value={"error": "auth denied"})
        return FakeResponse(value={"success": True})

    with FakeRelay(respond, secret=first) as relay:
        monkeypatch.setattr(Path, "read_text", read)
        client = RelayClient(environ=_environment(relay, ATOMIC_BRIDGE_ADMIN_SECRET_FILE=str(path)))
        invoke = (lambda: client.request("POST", "/tap", {"x": 1, "y": 2})) if operation == "request" else (
            lambda: client.download("/media/synthetic", io.BytesIO(), max_bytes=100))
        assert invoke().status == status
        assert len(relay.requests) == 1 and len(reads) == 2
        assert invoke().status == 200
        assert len(relay.requests) == 2 and len(reads) == 2
        assert relay.requests[1]["headers"]["Authorization"] == "Bearer " + second


@pytest.mark.parametrize("status", [400, 401, 403, 500, 503])
def test_http_errors_redact_admin_secret_and_never_retry(status, caplog):
    with FakeRelay(lambda request: FakeResponse(status=status, text="cause " + relay.expected_secret)) as relay:
        with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
            value = json.loads(RelayClient(environ=_environment(relay)).post("/tap", {}))
        assert value == {"error": f"HTTP {status}: cause [redacted]", "status": status}
        assert len(relay.requests) == 1
        assert relay.expected_secret not in caplog.text
        assert any(record.exc_info for record in caplog.records)


def test_http_error_uses_the_relay_json_error_message(caplog):
    message = "media path is outside allowed roots"
    with FakeRelay(lambda request: FakeResponse(status=403, value={"ok": False, "error": message})) as relay:
        with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
            result = RelayClient(environ=_environment(relay)).request("POST", "/media/register", {})
        assert result.status == 403
        assert result.value == {"error": "HTTP 403: media path is outside allowed roots", "status": 403}
        assert len(relay.requests) == 1
        assert message not in caplog.text
        assert any(record.exc_info for record in caplog.records)


@pytest.mark.parametrize("raw", [
    "raw refusal", '{"error": null}', '{"error": 17}', '{"error": false}',
    '{"message": "missing error"}', '["not an object"]', '{"error": ""}', "null",
])
def test_http_error_without_nonempty_string_error_retains_raw_body(raw):
    body = io.BytesIO(raw.encode())
    error = HTTPError("http://localhost", 500, "synthetic reason", {}, body)
    opener = _Opener(error=error)
    result = RelayClient(environ=_environment(), opener=opener).request("GET", "/ping")
    assert result.value == {"error": "HTTP 500: " + raw, "status": 500}
    assert len(opener.calls) == 1 and body.closed


@pytest.mark.parametrize("json_error", [False, True])
def test_http_error_message_redacts_secret_in_raw_and_json_paths(tmp_path, caplog, json_error):
    secret = "synthetic-" + 'quoted"credential'
    path = tmp_path / "admin.secret"
    path.touch(mode=0o600)
    path.write_text(secret)
    def respond(request):
        message = "refused " + secret
        return FakeResponse(status=500, value={"error": message}) if json_error else FakeResponse(status=500, text=message)
    with FakeRelay(respond, secret=secret) as relay:
        client = RelayClient(environ=_environment(relay, ATOMIC_BRIDGE_ADMIN_SECRET_FILE=str(path)))
        with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
            result = client.request("GET", "/ping")
        assert result.value == {"error": "HTTP 500: refused [redacted]", "status": 500}
        assert len(relay.requests) == 1
        assert secret not in json.dumps(result.value) + caplog.text
        assert json.dumps(secret)[1:-1] not in json.dumps(result.value) + caplog.text
        assert "refused" not in caplog.text


@pytest.mark.parametrize("failure", [OSError("synthetic unreadable body"), IncompleteRead(b"synthetic partial")])
def test_http_error_unreadable_body_uses_redacted_reason_and_keeps_status(tmp_path, caplog, failure):
    secret = "synthetic-" + 'quoted"credential'
    path = tmp_path / "admin.secret"
    path.touch(mode=0o600)
    path.write_text(secret)
    class Unreadable(io.BytesIO):
        def read(self, *args):
            raise failure
    body = Unreadable()
    error = HTTPError("http://localhost", 500, "refused " + secret, {}, body)
    opener = _Opener(error=error)
    client = RelayClient(environ=_environment(ATOMIC_BRIDGE_ADMIN_SECRET_FILE=str(path)), opener=opener)
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = client.request("GET", "/ping")
    assert result.status == 500
    assert result.value == {"error": "HTTP 500: refused [redacted]", "status": 500}
    assert len(opener.calls) == 1 and body.closed
    assert secret not in json.dumps(result.value) + caplog.text
    assert "synthetic unreadable" not in caplog.text and "synthetic partial" not in caplog.text
    assert any(record.exc_info for record in caplog.records)


def test_response_body_redacts_admin_secret_recursively():
    with FakeRelay(lambda request: FakeResponse(value={"nested": [relay.expected_secret, {"message": "echo " + relay.expected_secret}]})) as relay:
        value = RelayClient(environ=_environment(relay)).request("GET", "/ping").value
        assert value == {"nested": ["[redacted]", {"message": "echo [redacted]"}]}


@pytest.mark.parametrize("contents", [None, "", " \n\t"])
@pytest.mark.parametrize("operation", ["request", "download"])
def test_attack11_missing_or_empty_admin_file_returns_fixed_error_without_http(tmp_path, contents, operation, caplog):
    path = tmp_path / "absent.secret"
    if contents is not None:
        path.write_text(contents)
        path.chmod(0o600)
    opener = _Opener()
    client = RelayClient(environ=_environment(ATOMIC_BRIDGE_ADMIN_SECRET_FILE=str(path)), opener=opener)
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = client.request("GET", "/ping") if operation == "request" else client.download("/media/synthetic", io.BytesIO(), max_bytes=100)
    assert result.value == {"error": MISSING_SECRET}
    assert opener.calls == []
    assert any(record.exc_info for record in caplog.records)


def test_unreadable_secret_returns_clear_sanitized_error(monkeypatch, caplog):
    def denied(*args, **kwargs):
        raise PermissionError("synthetic-private-credential")

    monkeypatch.setattr(Path, "read_text", denied)
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        value = RelayClient(environ=_environment()).request("GET", "/ping").value
    assert "Admin secret file could not be read" in value["error"]
    assert "synthetic-private" not in str(value) + caplog.text
    assert any(record.exc_info for record in caplog.records)


def test_configured_timeout_and_per_request_override_are_applied():
    opener = _Opener()
    client = RelayClient(environ=_environment(ANDROID_BRIDGE_TIMEOUT="7.5"), opener=opener)
    client.get("/ping")
    client.get("/bridge/status", timeout=2)
    assert [timeout for request, timeout in opener.calls] == [7.5, 2]


@pytest.mark.parametrize("error", [ConnectionRefusedError(61, "Connection refused"), socket.timeout("timed out"), URLError("synthetic unavailable"), RemoteDisconnected("synthetic disconnect")])
def test_transport_failures_return_json_and_log_stack(error, caplog):
    opener = _Opener(error=error)
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        value = json.loads(RelayClient(environ=_environment(), opener=opener).post("/tap", {}))
    assert "error" in value and len(opener.calls) == 1
    assert any(record.exc_info for record in caplog.records)


@pytest.mark.parametrize("body", [b"not-json", b"[]", b"\xff"])
def test_malformed_success_response_is_visible(body, caplog):
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        value = json.loads(RelayClient(environ=_environment(), opener=_Opener(body)).get("/ping"))
    assert "error" in value
    assert any(record.exc_info for record in caplog.records)


@pytest.mark.parametrize("environment", [
    {"ANDROID_BRIDGE_TIMEOUT": "0"}, {"ANDROID_BRIDGE_TIMEOUT": "nan"}, {"ANDROID_BRIDGE_TIMEOUT": "bad"},
    {"ANDROID_BRIDGE_URL": ""}, {"ANDROID_BRIDGE_URL": "file:///private"},
    {"ANDROID_BRIDGE_URL": "http://user:password@localhost"},
])
def test_invalid_configuration_fails_without_http(environment):
    opener = _Opener()
    result = json.loads(RelayClient(environ=_environment(**environment), opener=opener).get("/ping"))
    assert "error" in result and opener.calls == []


@pytest.mark.parametrize("url", ["https://127.0.0.1", "http://outside.example", "http://127.0.0.2", "http://0.0.0.0", "http://localhost?", "http://localhost#", "http://localhost\n"])
@pytest.mark.parametrize("operation", ["request", "download"])
def test_attack11_nonloopback_or_unsafe_url_is_configuration_error_without_http(url, operation):
    opener = _Opener()
    client = RelayClient(environ=_environment(ANDROID_BRIDGE_URL=url), opener=opener)
    result = client.request("GET", "/ping") if operation == "request" else client.download("/media/synthetic", io.BytesIO(), max_bytes=100)
    assert "ANDROID_BRIDGE_URL" in result.value["error"]
    assert opener.calls == []


def test_phone_failure_passes_through_unchanged():
    failure = {"success": False, "code": "unsupported_command", "error": "Unsupported command", "result": {}}
    opener = _Opener(json.dumps(failure).encode())
    assert json.loads(RelayClient(environ=_environment(), opener=opener).post("/return_to_hermes")) == failure


def test_redirect_cannot_forward_credentials_to_another_target():
    with FakeRelay() as target:
        with FakeRelay(lambda request: FakeResponse(status=302, text="redirect", headers={"Location": target.url + "/stolen"})) as relay:
            result = json.loads(RelayClient(environ=_environment(relay)).post("/tap", {}))
            assert result["status"] == 302 and len(relay.requests) == 1
            assert target.requests == [] and target.rejected == 0


def test_http_error_without_body_redacts_reason():
    error = HTTPError("http://localhost", 500, "synthetic-" + "auth", {}, io.BytesIO())
    client = RelayClient(environ=_environment(), opener=_Opener(error=error))
    assert json.loads(client.get("/ping")) == {"error": "HTTP 500: [redacted]", "status": 500}


def test_auth_refresh_failure_keeps_original_status_and_server_cause(tmp_path):
    path = tmp_path / "admin.secret"
    path.write_text("synthetic-" + "stale")
    path.chmod(0o600)

    def respond(request):
        path.unlink()
        return FakeResponse(status=401, text="original auth cause")

    with FakeRelay(respond, secret=path.read_text()) as relay:
        client = RelayClient(environ=_environment(relay, ATOMIC_BRIDGE_ADMIN_SECRET_FILE=str(path)))
        actual = json.loads(client.post("/tap", {}))
        assert actual["status"] == 401 and "original auth cause" in actual["error"]
        assert "credential refresh failed" in actual["error"] and len(relay.requests) == 1


@pytest.mark.parametrize("status", [200, 500])
def test_json_escaped_admin_secret_is_redacted_in_bodies_and_errors(tmp_path, status, caplog):
    path = tmp_path / "escaped.secret"
    secret = "synthetic-" + 'quoted"credential'
    path.write_text(secret)
    path.chmod(0o600)
    with FakeRelay(lambda request: FakeResponse(status=status, value={"message": secret}), secret=secret) as relay:
        with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
            value = RelayClient(environ=_environment(relay, ATOMIC_BRIDGE_ADMIN_SECRET_FILE=str(path))).request("GET", "/ping").value
        assert "[redacted]" in json.dumps(value)
        assert secret not in json.dumps(value) + caplog.text
        assert json.dumps(secret)[1:-1] not in json.dumps(value)
