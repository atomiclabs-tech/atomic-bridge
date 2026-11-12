# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import argparse
import asyncio
import io
import json
import os
from pathlib import Path
import secrets
import urllib.error

import pytest
from aiohttp import ClientTimeout, web
from aiohttp.test_utils import TestClient, TestServer

from atomic_relay import cli_remote
from session_cases import cli_env


@pytest.mark.parametrize("command", ["pair", "status"])
def test_remote_command_uses_default_working_directory_env_file(tmp_path, monkeypatch, command):
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: tmp_path / "isolated-home"))
    for name in list(os.environ):
        if name.startswith("ATOMIC_RELAY_"):
            monkeypatch.delenv(name)
    cli_env(tmp_path)
    monkeypatch.chdir(tmp_path)
    parser = argparse.ArgumentParser()
    cli_remote.add_commands(parser.add_subparsers(dest="command", required=True))
    synthetic = secrets.token_hex(3).upper()
    options = ["--register-code", synthetic] if command == "pair" else []
    args = parser.parse_args([command, *options])
    supplied = []
    def request(port, path, body=None, **kwargs):
        if path == "/health":
            return 200, {"status": "ok"}
        supplied.append(kwargs.get("authorization") == "Bearer " + (tmp_path / "admin.secret").read_text().strip())
        return (200, {"ok": True, "code": synthetic}) if command == "pair" else (200, {"phone_connected": True})
    monkeypatch.setattr(cli_remote, "_request", request)
    assert getattr(cli_remote, command)(args)[0] == 0
    assert supplied == [True]


@pytest.mark.parametrize("options, ttl", [([], 30 * 86400), (["--ttl", "never"], 0)])
def test_pair_defaults_omit_grants_and_never_remains_explicit(tmp_path, monkeypatch, options, ttl):
    parser = argparse.ArgumentParser()
    cli_remote.add_commands(parser.add_subparsers(dest="command", required=True))
    synthetic = secrets.token_hex(3).upper()
    args = parser.parse_args(["pair", "--register-code", synthetic, "--env-file", str(cli_env(tmp_path)), *options])
    assert args.ttl == ("30d" if not options else "never")
    omitted = args.grants is None
    assert omitted
    registered = []
    def request(port, path, body=None, **kwargs):
        if path == "/health":
            return 200, {"status": "ok"}
        assert kwargs["authorization"] == "Bearer " + (tmp_path / "admin.secret").read_text().strip()
        registered.append(body)
        return 200, {"ok": True, "code": synthetic}
    monkeypatch.setattr(cli_remote, "_request", request)
    assert cli_remote.pair(args) == (0, {"ok": True})
    assert len(registered) == 1
    assert set(registered[0]) == {"code", "ttl_seconds"}
    assert registered[0]["ttl_seconds"] == ttl


def test_duration_grant_parsing():
    assert cli_remote.parse_duration("30d") == 30 * 86400
    assert cli_remote.parse_duration("never") == 0
    assert cli_remote.parse_grants("bridge=never,terminal=7d") == {"bridge": 0, "terminal": 7 * 86400}
    for value in ("bad", "-1", "1.5h"):
        with pytest.raises(ValueError):
            cli_remote.parse_duration(value)
    with pytest.raises(ValueError):
        cli_remote.parse_grants("bad")


def test_pair_checks_health_refusal_and_redacts_server_echo(monkeypatch, tmp_path):
    args = argparse.Namespace(register_code="ABC123", ttl="never", grants="bridge=never", port=1, env_file=cli_env(tmp_path))
    responses = iter([(200, {"status": "ok"}), (400, {"ok": False, "error": "refused ABC123"})])
    seen = []
    def request(port, path, body=None, **kwargs):
        supplied = kwargs.get("authorization")
        expected = (tmp_path / "admin.secret").read_text().strip()
        seen.append((path, supplied == "Bearer " + expected if supplied is not None else False))
        return next(responses)
    monkeypatch.setattr(cli_remote, "_request", request)
    code, body = cli_remote.pair(args)
    assert code == 1 and body == {"ok": False, "error": "refused [redacted]"}
    assert seen == [("/health", False), ("/pairing/register", True)]
    monkeypatch.setattr(cli_remote, "_request", lambda *args, **kwargs: (503, {"status": "bad"}))
    assert cli_remote.pair(args)[0] == 1


def test_http_client_timeout_status_and_invalid_payload(monkeypatch):
    seen = []
    class Response(io.BytesIO):
        status = 200
    def opened(self, request, *, timeout):
        seen.append((request.full_url, timeout, request.method))
        return Response(json.dumps({"status": "ok"}).encode())
    monkeypatch.setattr(cli_remote.urllib.request.OpenerDirector, "open", opened)
    assert cli_remote._request(1, "/health") == (200, {"status": "ok"})
    assert seen == [("http://127.0.0.1:1/health", 5, "GET")]
    def refused(self, request, *, timeout):
        raise urllib.error.HTTPError(request.full_url, 503, "no phone", {}, Response(b'{"phone_connected":false}'))
    monkeypatch.setattr(cli_remote.urllib.request.OpenerDirector, "open", refused)
    assert cli_remote._request(1, "/bridge/status")[0] == 503
    monkeypatch.setattr(cli_remote.urllib.request.OpenerDirector, "open", lambda *args, **kwargs: Response(b"[]"))
    with pytest.raises(ValueError, match="non-object"):
        cli_remote._request(1, "/health")


def test_loopback_client_ignores_ambient_proxy_discovery(monkeypatch):
    async def check():
        proxied = []
        async def proxy(request):
            proxied.append(request.method)
            return web.json_response({"error": "synthetic proxy intercepted"}, status=502)
        async def health(request):
            return web.json_response({"status": "ok"})
        proxy_app, target_app = web.Application(), web.Application()
        proxy_app.router.add_route("*", "/{path:.*}", proxy)
        target_app.router.add_get("/health", health)
        async with TestClient(TestServer(proxy_app, port=0), timeout=ClientTimeout(total=2)) as proxy_client:
            async with TestClient(TestServer(target_app, port=0), timeout=ClientTimeout(total=2)) as target:
                monkeypatch.setattr(cli_remote.urllib.request, "_opener", None)
                monkeypatch.setattr(cli_remote.urllib.request, "getproxies", lambda: {"http": str(proxy_client.make_url("/"))})
                monkeypatch.setattr(cli_remote.urllib.request, "proxy_bypass", lambda host: False)
                assert await asyncio.to_thread(cli_remote._request, target.port, "/health") == (200, {"status": "ok"})
                assert proxied == []
    asyncio.run(check())


@pytest.mark.parametrize("status", [301, 302, 303, 307, 308])
@pytest.mark.parametrize("body", [None, {"ttl_seconds": 0}])
def test_loopback_client_refuses_redirects(status, body):
    async def check():
        redirected = []
        async def destination(request):
            redirected.append(request.method)
            return web.json_response({"ok": True})
        destination_app = web.Application()
        destination_app.router.add_route("*", "/target", destination)
        async with TestClient(TestServer(destination_app, port=0), timeout=ClientTimeout(total=2)) as target:
            async def redirect(request):
                return web.json_response({"error": "redirect refused"}, status=status, headers={"Location": str(target.make_url("/target"))})
            source_app = web.Application()
            source_app.router.add_route("*", "/source", redirect)
            async with TestClient(TestServer(source_app, port=0), timeout=ClientTimeout(total=2)) as source:
                assert await asyncio.to_thread(cli_remote._request, source.port, "/source", body) == (status, {"error": "redirect refused"})
                assert redirected == []
    asyncio.run(check())
