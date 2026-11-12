# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import logging
import secrets
from contextlib import asynccontextmanager
from unittest.mock import Mock

import pytest
from aiohttp import ClientTimeout, web
from aiohttp.test_utils import TestClient, TestServer, make_mocked_request

from atomic_relay.config import DEFAULTS, load_config
from atomic_relay.web.app import create_app
from atomic_relay.web.guard import FORWARDED_HEADERS, is_loopback, loopback_middleware
from atomic_relay.web.runtime import RUNTIME
from atomic_relay.web import routes_bridge
from fake_phone import FakePhone, session_payload


@asynccontextmanager
async def attack_application(tmp_path):
    secret = secrets.token_urlsafe(32)
    secret_file = tmp_path / "admin.secret"
    secret_file.write_text(secret + "\n")
    secret_file.chmod(0o600)
    settings = {
        "ATOMIC_RELAY_PORT": "0", "ATOMIC_RELAY_SESSIONS_FILE": "",
        "ATOMIC_RELAY_LOG_FILE": str(tmp_path / "relay.log"),
        "ATOMIC_RELAY_MEDIA_DIR": str(tmp_path / "media"),
    }
    # Keep the attack harness runnable against the pre-credential server for proof.
    if "ATOMIC_RELAY_ADMIN_SECRET_FILE" in DEFAULTS:
        settings["ATOMIC_RELAY_ADMIN_SECRET_FILE"] = str(secret_file)
    app = create_app(load_config(environ=settings))
    async with TestClient(TestServer(app, port=0), timeout=ClientTimeout(total=2)) as client:
        yield app, client, secret


def test_attack_browser_pairing_registers_nothing(tmp_path, caplog):
    async def check():
        async with attack_application(tmp_path) as (app, client, secret):
            code = secrets.token_hex(3).upper()
            response = await client.post("/pairing/register", data='{"code":"' + code + '"}', headers={
                "Content-Type": "text/plain", "Origin": "https://synthetic.invalid",
                "Authorization": "Bearer " + secret,
            })
            assert response.status in {403, 415}
            assert app[RUNTIME].pairing.consume_code(code) is None
            assert secret not in caplog.text and code not in caplog.text
            assert "https://synthetic.invalid" not in caplog.text
    asyncio.run(check())


@pytest.mark.parametrize("forwarded", FORWARDED_HEADERS)
def test_attack_forwarded_command_refused_and_direct_admin_succeeds(tmp_path, caplog, forwarded):
    async def check():
        async with attack_application(tmp_path) as (app, client, secret):
            session = app[RUNTIME].sessions.create_session("Synthetic", "attack-device", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            await phone.status()
            auth = {"Authorization": "Bearer " + secret}
            response = await client.post("/tap", json={"x": 1, "y": 2}, headers={**auth, forwarded: "private-header"})
            assert response.status == 403
            pending = asyncio.create_task(client.post("/tap", json={"x": 1, "y": 2}, headers=auth))
            command = await phone.receive()
            await phone.respond(command, result={"success": True})
            assert (await asyncio.wait_for(pending, timeout=2)).status == 200
            assert secret not in caplog.text and session.token not in caplog.text
            assert "private-header" not in caplog.text
            await phone.close()
    asyncio.run(check())


@pytest.mark.parametrize(("method", "path"), [
    ("POST", "/tap"), ("GET", "/bridge/devices"), ("GET", "/notifications/recent"),
    ("GET", "/sessions"), ("POST", "/pairing/register"), ("POST", "/media/register"),
])
def test_attack_phone_session_cannot_authorize_admin_families(tmp_path, caplog, method, path):
    async def check():
        async with attack_application(tmp_path) as (app, client, secret):
            session = app[RUNTIME].sessions.create_session("Synthetic", "attack-device", ttl_seconds=0)
            response = await client.request(method, path, json={} if method == "POST" else None,
                                            headers={"Authorization": "Bearer " + session.token})
            assert response.status == 401
            assert (await response.json())["error"] == "admin credential required"
            assert secret not in caplog.text and session.token not in caplog.text
    asyncio.run(check())


@pytest.mark.parametrize("path", ["/", "/ws"])
def test_attack_websocket_origin_refused_before_authentication(tmp_path, caplog, path):
    from aiohttp import WSServerHandshakeError
    async def check():
        async with attack_application(tmp_path) as (app, client, secret):
            with pytest.raises(WSServerHandshakeError) as refused:
                await client.ws_connect(path, headers={"Origin": "https://private-origin.invalid"})
            assert refused.value.status == 403
            assert not app[RUNTIME].clients
            assert secret not in caplog.text and "https://private-origin.invalid" not in caplog.text
    asyncio.run(check())


def test_admin_checks_run_in_transport_origin_json_credential_order(tmp_path, caplog):
    async def check():
        async with attack_application(tmp_path) as (app, client, secret):
            code = secrets.token_hex(3).upper()
            body = '{"code":"' + code + '"}'
            headers = {"X-Real-IP": "private-peer", "Origin": "private-origin", "Content-Type": "text/plain"}
            for expected, reason, remove in (
                (403, "loopback only", "X-Real-IP"),
                (403, "browser origin refused", "Origin"),
                (415, "application/json required", "Content-Type"),
                (401, "admin credential required", None),
            ):
                if expected == 401:
                    headers["Content-Type"] = "application/json; charset=utf-8"
                response = await client.post("/pairing/register", headers=headers, data=body)
                assert response.status == expected
                assert await response.json() == {"ok": False, "error": reason}
                assert app[RUNTIME].pairing.consume_code(code) is None
                if remove:
                    headers.pop(remove)
            headers["Authorization"] = "Bearer " + secret
            response = await client.post("/pairing/register", headers=headers, data=body)
            assert response.status == 200
            assert app[RUNTIME].pairing.consume_code(code) is not None
            assert code not in caplog.text and "private-peer" not in caplog.text and "private-origin" not in caplog.text
    asyncio.run(check())


@pytest.mark.parametrize("method,path", [
    *(("GET", path) for path in routes_bridge.GET_PATHS),
    *(("POST", path) for path in routes_bridge.POST_PATHS),
    ("GET", "/bridge/status"), ("GET", "/bridge/devices"), ("POST", "/bridge/select-active"),
    ("GET", "/notifications/recent"), ("GET", "/notifications/wait"), ("GET", "/sessions"),
    ("PATCH", "/sessions/synthetic-prefix"), ("DELETE", "/sessions/synthetic-prefix"),
    ("POST", "/pairing/register"), ("POST", "/pairing/approve"), ("POST", "/media/register"),
    ("POST", "/media/synthetic-token/sensitive"),
])
def test_every_admin_route_refuses_phone_before_session_lookup(tmp_path, method, path, monkeypatch):
    async def check():
        async with attack_application(tmp_path) as (app, client, _):
            session = app[RUNTIME].sessions.create_session("Synthetic", "admin-refusal", ttl_seconds=0)
            def forbidden_lookup(token):
                pytest.fail("Admin refusals must not look up phone sessions")
            monkeypatch.setattr(app[RUNTIME].sessions, "get_session", forbidden_lookup)
            kwargs = {"json": {}} if method in {"POST", "PATCH"} else {}
            response = await client.request(method, path, headers={"Authorization": "Bearer " + session.token}, **kwargs)
            assert response.status == 401
            assert (await response.json())["error"] == "admin credential required"
    asyncio.run(check())


@pytest.mark.parametrize("method", ["POST", "PATCH", "DELETE"])
@pytest.mark.parametrize("content_type", ["text/plain", "application/x-www-form-urlencoded"])
def test_non_json_admin_bodies_refused_before_parsing(tmp_path, method, content_type):
    async def check():
        async with attack_application(tmp_path) as (_, client, secret):
            path = "/pairing/register" if method == "POST" else "/sessions/synthetic-prefix"
            response = await client.request(method, path, data="{}", headers={
                "Authorization": "Bearer " + secret, "Content-Type": content_type,
            })
            assert response.status == 415
            assert (await response.json())["error"] == "application/json required"
    asyncio.run(check())


@pytest.mark.parametrize("value", [None, "", "Basic synthetic-invalid", "Bearer ", "Bearer synthetic-invalid"])
def test_admin_missing_malformed_and_wrong_credentials_are_json_refusals(tmp_path, value):
    async def check():
        async with attack_application(tmp_path) as (_, client, _):
            response = await client.get("/sessions", headers={} if value is None else {"Authorization": value})
            assert response.status == 401
            assert await response.json() == {"error": "admin credential required"}
    asyncio.run(check())


def test_refusal_logs_use_templates_instead_of_secret_route_parameters(tmp_path, caplog):
    async def check():
        async with attack_application(tmp_path) as (_, client, secret):
            response = await client.post("/media/" + secret + "/sensitive", json={}, headers={"Origin": ""})
            assert response.status == 403
            assert "route=/media/{token}/sensitive" in caplog.text
            assert secret not in caplog.text
    asyncio.run(check())


def request_for(peer, headers=None, path="/private"):
    transport = Mock()
    transport.get_extra_info.return_value = peer
    return make_mocked_request("GET", path, headers=headers, transport=transport)


@pytest.mark.parametrize("peer", [("127.0.0.1", 1000), ("::1", 1000, 0, 0)])
def test_loopback_peers_allowed(peer):
    assert is_loopback(request_for(peer))


@pytest.mark.parametrize("peer", [("192.0.2.5", 1000), ("127.0.0.2", 1000), None])
def test_non_loopback_or_missing_peers_denied(peer):
    assert not is_loopback(request_for(peer))


@pytest.mark.parametrize("header", FORWARDED_HEADERS)
def test_each_forwarded_header_refused_with_safe_warning(header, caplog, monkeypatch):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    request = request_for(("127.0.0.1", 1000), {header.lower(): "synthetic-header-value"})
    assert not is_loopback(request)
    async def handler(request):
        pytest.fail("protected handler must not run")
    with caplog.at_level(logging.WARNING, logger="atomic_relay.web"):
        response = asyncio.run(loopback_middleware({"/private"})(request, handler))
    assert response.status == 403
    assert response.text == '{"error": "loopback only"}'
    assert header in caplog.text
    assert "127.0.0.1" in caplog.text
    assert "synthetic-header-value" not in caplog.text
    assert caplog.records[-1].levelno == logging.WARNING


def test_remote_peer_denied_and_unprotected_route_unchanged(caplog, monkeypatch):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    async def handler(request):
        return web.Response(text="allowed")
    guard = loopback_middleware({"/private"})
    with caplog.at_level(logging.WARNING, logger="atomic_relay.web"):
        response = asyncio.run(guard(request_for(("192.0.2.5", 1000)), handler))
    assert response.status == 403
    assert "192.0.2.5" in caplog.text
    response = asyncio.run(guard(request_for(("192.0.2.5", 1000), path="/public"), handler))
    assert response.status == 200
    assert response.text == "allowed"


def test_guard_is_a_reusable_aiohttp_middleware():
    async def check():
        app = web.Application(middlewares=[loopback_middleware({"/private", "/private/{item}"})])
        async def handler(request):
            return web.json_response({"ok": True})
        app.router.add_get("/private", handler)
        app.router.add_get("/private/{item}", handler)
        app.router.add_get("/public", handler)
        client = TestClient(TestServer(app, port=0), timeout=ClientTimeout(total=2))
        await asyncio.wait_for(client.start_server(), timeout=2)
        try:
            assert (await client.get("/private")).status == 200
            response = await client.get("/private", headers={"X-Real-IP": "synthetic"})
            assert response.status == 403
            assert await response.json() == {"error": "loopback only"}
            assert (await client.get("/private/item")).status == 200
            assert (await client.get("/private/item", headers={"Forwarded": "synthetic"})).status == 403
            assert (await client.get("/public", headers={"Forwarded": "synthetic"})).status == 200
        finally:
            await asyncio.wait_for(client.close(), timeout=2)
    asyncio.run(check())
