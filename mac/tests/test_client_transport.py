# no_parallel: opens a loopback test server on port 0.
import hashlib
import io
import logging
import socket
from http.client import IncompleteRead, RemoteDisconnected
from urllib.error import HTTPError, URLError

import pytest

from atomic_bridge.client import RelayClient
from fake_relay import admin_environment, FakeRelay, FakeResponse


class Response(io.BytesIO):
    def __init__(self, body=b"synthetic bytes", *, status=200, headers=None, error=None):
        super().__init__(body)
        self.status = status
        self.headers = {"Content-Type": "image/png"} if headers is None else headers
        self.error = error
        self.read_sizes = []

    def read(self, size=-1):
        self.read_sizes.append(size)
        if self.error:
            raise self.error
        return super().read(size)


class Opener:
    def __init__(self, response=None, error=None):
        self.response = response if response is not None else Response()
        self.error = error
        self.calls = []

    def open(self, request, *, timeout):
        self.calls.append((request, timeout))
        if self.error:
            raise self.error
        return self.response


class Sink(io.BytesIO):
    def __init__(self, *, error=None, written=None):
        super().__init__()
        self.error = error
        self.written = written
        self.write_sizes = []

    def write(self, value):
        self.write_sizes.append(len(value))
        if self.error:
            raise self.error
        if self.written is not None:
            return self.written
        return super().write(value)


def client_with(opener, **values):
    return RelayClient(environ={**admin_environment(), **values}, opener=opener)


def test_download_streams_bounded_chunks_hashes_and_normalizes_content_type():
    body = bytes(range(256)) * 800
    response = Response(body, headers={
        "Content-Type": "IMAGE/PNG; charset=binary", "Content-Length": str(len(body)),
    })
    opener, sink = Opener(response), Sink()
    result = client_with(opener, ANDROID_BRIDGE_TIMEOUT="7.5").download(
        "/media/synthetic-media", sink, max_bytes=8 * 1024 * 1024,
    )
    assert result.status == 200
    assert result.value == {"size": len(body), "sha256": hashlib.sha256(body).hexdigest(), "content_type": "image/png"}
    assert sink.getvalue() == body
    assert len(sink.write_sizes) > 1
    assert max(sink.write_sizes) <= 64 * 1024
    assert all(0 < size <= 64 * 1024 for size in response.read_sizes)
    request, timeout = opener.calls[0]
    assert timeout == 7.5
    assert request.method == "GET"
    assert request.get_header("Authorization") == "Bearer synthetic-auth"
    assert len(opener.calls) == 1
    assert response.closed
    assert not sink.closed


def test_download_works_over_real_loopback_http_and_with_caller_file(tmp_path):
    body = "synthetic payload " * 10000
    with FakeRelay(lambda request: FakeResponse(text=body, headers={"Content-Type": "image/jpeg"})) as relay:
        client = RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()})
        path = tmp_path / "download"
        with path.open("wb") as sink:
            result = client.download("/media/synthetic-media", sink, max_bytes=256 * 1024 * 1024)
            assert not sink.closed
        assert result.status == 200
        assert result.value == {"size": len(body), "sha256": hashlib.sha256(body.encode()).hexdigest(), "content_type": "image/jpeg"}
        assert path.read_bytes() == body.encode()
        assert len(relay.requests) == 1
        assert relay.requests[0]["headers"]["Authorization"] == "Bearer synthetic-auth"


def test_download_at_exact_cap_and_empty_file_are_valid():
    for body in (b"", b"12345"):
        sink = Sink()
        result = client_with(Opener(Response(body))).download("/media/synthetic", sink, max_bytes=5)
        assert result.value["size"] == len(body)
        assert sink.getvalue() == body


@pytest.mark.parametrize("limit", [5, 8 * 1024 * 1024])
def test_download_refuses_advertised_oversize_before_reading_or_writing(limit, caplog):
    response = Response(headers={"Content-Type": "image/png", "Content-Length": str(limit + 1)})
    sink = Sink()
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = client_with(Opener(response)).download("/media/synthetic-private-url", sink, max_bytes=limit)
    assert result.value["error"] == f"Relay download failed: Download response exceeds byte limit ({limit} bytes)"
    assert "synthetic-private" not in str(result.value) + caplog.text
    assert "synthetic-auth" not in str(result.value) + caplog.text
    assert result.status == 200
    assert response.read_sizes == []
    assert sink.write_sizes == []
    assert not sink.closed


def test_download_enforces_cap_without_content_length_and_keeps_sink_caller_owned():
    response, sink = Response(b"x" * 150000), Sink()
    opener = Opener(response)
    result = client_with(opener).download("/media/synthetic", sink, max_bytes=100000)
    assert result.value["error"] == "Relay download failed: Download response exceeds byte limit (100000 bytes)"
    assert len(sink.getvalue()) == 64 * 1024
    assert len(sink.getvalue()) <= 100000
    assert response.read_sizes == [65536, 34465]
    assert len(opener.calls) == 1
    assert response.closed
    assert not sink.closed


@pytest.mark.parametrize("limit", [0, -1, True, 1.5, None])
def test_download_invalid_cap_fails_before_http(limit):
    opener = Opener()
    result = client_with(opener).download("/media/synthetic", Sink(), max_bytes=limit)
    assert "error" in result.value
    assert opener.calls == []


@pytest.mark.parametrize(("headers", "reason"), [
    ({}, "missing its content type"),
    ({"Content-Type": ""}, "missing its content type"),
    ({"Content-Type": "; charset=synthetic-private"}, "invalid content type"),
    ({"Content-Type": "synthetic-private-type"}, "invalid content type"),
    ({"Content-Type": "image/png", "Content-Length": "-1"}, "invalid content length"),
    ({"Content-Type": "image/png", "Content-Length": "synthetic-private-length"}, "invalid content length"),
    ({"Content-Type": "image/png", "Content-Length": "1"}, "does not match its content length"),
    ({"Content-Type": "image/png", "Content-Length": "99"}, "does not match its content length"),
])
def test_download_rejects_invalid_metadata_and_length_mismatch(headers, reason, caplog):
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = client_with(Opener(Response(headers=headers))).download("/media/synthetic-private-url", Sink(), max_bytes=100)
    assert reason in result.value["error"]
    assert "synthetic-private" not in str(result.value) + caplog.text
    assert "synthetic-auth" not in str(result.value) + caplog.text
    assert any(record.exc_info for record in caplog.records)


@pytest.mark.parametrize("failure", [
    OSError("synthetic-private"), ValueError("synthetic-private"), TypeError("synthetic-private"),
    BufferError("synthetic-private"),
])
def test_download_sink_failure_is_structured_sanitized_and_not_retried(failure, caplog):
    opener, sink = Opener(), Sink(error=failure)
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = client_with(opener).download("/media/synthetic-private-url", sink, max_bytes=100)
    assert result.value["error"] == f"Relay download failed: {type(failure).__name__}"
    assert result.status == 200
    assert len(opener.calls) == 1
    assert len(sink.write_sizes) == 1
    assert "synthetic-private" not in str(result.value) + caplog.text
    assert "synthetic-auth" not in str(result.value) + caplog.text
    assert any(record.exc_info for record in caplog.records)
    assert not sink.closed


@pytest.mark.parametrize("written", [0, 1, True, "wrong"])
def test_download_rejects_unchecked_or_short_sink_write(written):
    result = client_with(Opener()).download("/media/synthetic", Sink(written=written), max_bytes=100)
    assert "error" in result.value


@pytest.mark.parametrize("failure", [
    ConnectionRefusedError("synthetic-private"), socket.timeout("synthetic-private"),
    URLError("synthetic-private"), RemoteDisconnected("synthetic-private"),
    ValueError("synthetic-private"),
])
def test_download_transport_failure_is_sanitized_and_not_retried(failure, caplog):
    opener = Opener(error=failure)
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = client_with(opener).download("/media/synthetic-private-url", Sink(), max_bytes=100)
    assert result.status is None
    assert result.value["error"] == f"Relay download failed: {type(failure).__name__}"
    assert len(opener.calls) == 1
    assert "synthetic-private" not in str(result.value) + caplog.text
    assert any(record.exc_info for record in caplog.records)


def test_download_read_failure_keeps_status_and_sanitizes_partial_data(caplog):
    response = Response(error=IncompleteRead(b"synthetic-private-data", 100))
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = client_with(Opener(response)).download("/media/synthetic-private-url", Sink(), max_bytes=100)
    assert result.status == 200
    assert "error" in result.value
    assert "synthetic-private" not in str(result.value) + caplog.text
    assert response.closed


def test_download_rejects_nonbinary_chunks():
    class InvalidResponse(Response):
        def read(self, size=-1):
            return "synthetic-private-data"

    result = client_with(Opener(InvalidResponse())).download("/media/synthetic", Sink(), max_bytes=100)
    assert "error" in result.value
    assert "synthetic-private" not in str(result.value)


@pytest.mark.parametrize("status", [302, 400, 401, 403, 500])
def test_download_http_failure_does_not_read_error_body_or_retry(status, caplog):
    error_body = Response(b"synthetic-private-body synthetic-auth")
    failure = HTTPError("http://synthetic.invalid/media/synthetic-private-url", status, "synthetic-private-reason", {}, error_body)
    opener = Opener(error=failure)
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = client_with(opener).download("/media/synthetic-private-url", Sink(), max_bytes=100)
    assert result.status == status
    assert result.value["status"] == status
    assert "error" in result.value
    assert error_body.read_sizes == []
    assert error_body.closed
    assert len(opener.calls) == 1
    assert "synthetic-private" not in str(result.value) + caplog.text
    assert "synthetic-auth" not in str(result.value) + caplog.text
    assert any(record.exc_info for record in caplog.records)


def test_download_checks_status_even_for_injected_response():
    response, sink = Response(status=503), Sink()
    result = client_with(Opener(response)).download("/media/synthetic", sink, max_bytes=100)
    assert result.status == 503
    assert "error" in result.value
    assert response.read_sizes == []
    assert sink.write_sizes == []


@pytest.mark.parametrize("operation", ["download", "request"])
def test_new_transports_never_follow_real_http_redirects(operation):
    with FakeRelay() as target:
        with FakeRelay(lambda request: FakeResponse(status=302, text="synthetic-private", headers={"Location": target.url + "/other"})) as relay:
            client = RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()})
            result = (client.download("/media/synthetic", Sink(), max_bytes=100)
                      if operation == "download" else client.request("GET", "/health"))
            assert result.status == 302
            assert "error" in result.value
            assert len(relay.requests) == 1
            assert target.requests == []


def test_request_get_and_pairing_post_require_admin_bearer():
    with FakeRelay() as relay:
        client = RelayClient(environ=admin_environment(ANDROID_BRIDGE_URL=relay.url))
        body = {"code": "synthetic-only"}
        assert client.request("GET", "/health").value == {"success": True}
        assert client.request("GET", "/bridge/status", {"device": "synthetic"}).status == 200
        assert client.request("POST", "/pairing/register", body).status == 200
        assert [request["path"] for request in relay.requests] == ["/health", "/bridge/status", "/pairing/register"]
        assert relay.requests[1]["query"] == {"device": "synthetic"}
        assert relay.requests[2]["body"] == body
        assert all(request["headers"]["Authorization"] == "Bearer " + relay.expected_secret for request in relay.requests)


@pytest.mark.parametrize("url", ["http://127.0.0.1", "http://[::1]", "http://localhost"])
def test_request_accepts_loopback_with_admin(url):
    opener = Opener(Response(b'{"success": true}'))
    result = client_with(opener, ANDROID_BRIDGE_URL=url).request("GET", "/health", timeout=2)
    assert result.status == 200
    request, timeout = opener.calls[0]
    assert request.full_url == url + "/health"
    assert timeout == 2
    assert request.get_header("Authorization") == "Bearer synthetic-auth"


@pytest.mark.parametrize("url", [
    "https://localhost", "http://localhost.example", "http://127.0.0.2", "http://0.0.0.0",
    "http://127.0.0.1.evil", "http://[::ffff:127.0.0.1]", "http://2130706433",
    "http://user:password@127.0.0.1", "http://@127.0.0.1", "file:///private",
])
def test_request_refuses_nonliteral_loopback_or_url_credentials(url, caplog):
    opener = Opener()
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = client_with(opener, ANDROID_BRIDGE_URL=url).request("GET", "/health")
    assert "error" in result.value
    assert opener.calls == []
    assert "password" not in str(result.value) + caplog.text


@pytest.mark.parametrize("operation", ["download", "request"])
@pytest.mark.parametrize("path", ["https://synthetic.invalid/x", "//synthetic.invalid/x", "relative", "/health#fragment"])
def test_new_transports_refuse_nonrelative_paths_before_http(operation, path):
    opener = Opener()
    client = client_with(opener)
    result = (client.download(path, Sink(), max_bytes=100)
              if operation == "download" else client.request("GET", path))
    assert "error" in result.value
    assert opener.calls == []


@pytest.mark.parametrize("timeout", [0, -1, "nan", "synthetic-private"])
@pytest.mark.parametrize("operation", ["download", "request"])
def test_new_transports_validate_timeout_before_http(operation, timeout):
    opener = Opener()
    client = client_with(opener)
    result = (client.download("/media/synthetic", Sink(), max_bytes=100, timeout=timeout)
              if operation == "download" else client.request("GET", "/health", timeout=timeout))
    assert "error" in result.value
    assert "synthetic-private" not in str(result.value)
    assert opener.calls == []


@pytest.mark.parametrize("failure", [URLError("synthetic-private"), socket.timeout("synthetic-private")])
def test_request_transport_failure_is_structured_and_sanitized(failure, caplog):
    opener = Opener(error=failure)
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = client_with(opener).request("POST", "/pairing/register", {"code": "synthetic-private-code"})
    assert "error" in result.value
    assert "synthetic-private" not in str(result.value) + caplog.text
    assert any(record.exc_info for record in caplog.records)
    assert len(opener.calls) == 1


@pytest.mark.parametrize("body", [b"not-json synthetic-private", b"[]", b"\xff"])
def test_request_invalid_json_is_sanitized(body, caplog):
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = client_with(Opener(Response(body))).request("GET", "/health")
    assert "error" in result.value
    assert "synthetic-private" not in str(result.value) + caplog.text
    assert any(record.exc_info for record in caplog.records)


@pytest.mark.parametrize("status", [400, 401, 403, 503])
def test_request_http_errors_redact_admin_without_retry(status, caplog):
    with FakeRelay(lambda request: FakeResponse(status=status, text="synthetic-private-code")) as relay:
        client = RelayClient(environ=admin_environment(ANDROID_BRIDGE_URL=relay.url))
        with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
            result = client.request("POST", "/pairing/register", {"code": "synthetic-only"})
        assert result.status == status
        assert "error" in result.value
        assert "synthetic-private" not in caplog.text
        assert len(relay.requests) == 1
        assert relay.requests[0]["headers"]["Authorization"] == "Bearer " + relay.expected_secret
        assert any(record.exc_info for record in caplog.records)


@pytest.mark.parametrize("operation", ["request", "download", "register"])
@pytest.mark.parametrize("case", ["upper", "lower"])
def test_attack11_environment_proxies_are_ignored_on_every_transport(monkeypatch, operation, case):
    with FakeRelay() as proxy, FakeRelay(lambda request: FakeResponse(
            text="synthetic bytes", headers={"Content-Type": "application/octet-stream"})
            if request["path"].startswith("/media/") and request["method"] == "GET"
            else FakeResponse(value={"success": True})) as relay:
        for key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy", "NO_PROXY", "no_proxy"):
            monkeypatch.delenv(key, raising=False)
        for key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"):
            monkeypatch.setenv(key if case == "upper" else key.lower(), proxy.url)
        monkeypatch.setenv("NO_PROXY", "")
        monkeypatch.setenv("no_proxy", "")
        client = RelayClient(environ=admin_environment(ANDROID_BRIDGE_URL=relay.url))
        if operation == "download":
            result = client.download("/media/synthetic", Sink(), max_bytes=100)
        elif operation == "register":
            result = client.request("POST", "/media/register", {"path": "synthetic"})
        else:
            result = client.request("GET", "/ping")
        assert result.status == 200
        assert len(relay.requests) == 1 and relay.rejected == 0
        assert proxy.requests == [] and proxy.rejected == 0
