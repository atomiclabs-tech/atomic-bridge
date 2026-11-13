# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import json
import logging
import os
import secrets
import string
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from aiohttp import ClientTimeout
from aiohttp.test_utils import TestClient, TestServer, make_mocked_request

from atomic_relay.config import load_config
from atomic_relay.sessions.unlock_pin import UnlockPin
from atomic_relay.web.app import create_app
from atomic_relay.web.guard import admin_middleware
from atomic_relay.web.routes_bridge import POST_PATHS
from atomic_relay.web.runtime import RUNTIME
from fake_phone import FakePhone, session_payload
from session_cases import admin_headers, application, configuration


@pytest.fixture(autouse=True)
def pin_log_guard(caplog, monkeypatch):
    values = set()
    leaks = []
    original = UnlockPin.load
    original_write = write_pin
    original_message = logging.LogRecord.getMessage
    def read(path):
        value = original(path)
        values.add(value)
        return value
    def write(path, length=6):
        value = original_write(path, length)
        values.add(value)
        return value
    def safe_message(record):
        message = original_message(record)
        for value in values:
            if value in message:
                leaks.append(True)
                message = message.replace(value, "<PIN redacted>")
        return message
    monkeypatch.setattr(UnlockPin, "load", read)
    monkeypatch.setitem(globals(), "write_pin", write)
    monkeypatch.setattr(logging.LogRecord, "getMessage", safe_message)
    yield
    leaked = any(value in caplog.text for value in values)
    assert not leaks and not leaked, "A PIN appeared in relay logs (contents redacted)"


def write_pin(path, length=6):
    pin = "".join(secrets.choice(string.digits) for _ in range(length))
    path.write_text(pin)
    path.chmod(0o600)
    return pin


@asynccontextmanager
async def unlock_application(tmp_path, path):
    configuration(tmp_path)
    config = load_config(environ={
        "ATOMIC_RELAY_PORT": "0", "ATOMIC_RELAY_SESSIONS_FILE": "",
        "ATOMIC_RELAY_ADMIN_SECRET_FILE": str(tmp_path / "admin.secret"),
        "ATOMIC_RELAY_LOG_FILE": str(tmp_path / "relay.log"),
        "ATOMIC_RELAY_MEDIA_DIR": str(tmp_path / "media"),
        "ATOMIC_RELAY_UNLOCK_PIN_FILE": str(path),
    })
    app = create_app(config)
    server = TestServer(app, port=0)
    client = TestClient(server, timeout=ClientTimeout(total=2),
                        headers={**admin_headers(app), "Content-Type": "application/json"})
    await asyncio.wait_for(server.start_server(access_log=None), timeout=2)
    await asyncio.wait_for(client.start_server(), timeout=2)
    try:
        yield app, client
    finally:
        await asyncio.wait_for(client.close(), timeout=2)


def test_unlock_route_is_registered_by_real_app(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (_, client):
            response = await client.post("/unlock", json={})
            assert response.status == 503
            assert await response.json() == {"error": "unlock PIN not configured"}
    asyncio.run(check())


@pytest.mark.parametrize("selector_source", ["query", "body"])
def test_real_config_dispatches_only_file_pin_and_rereads_each_call(tmp_path, selector_source, caplog):
    path = tmp_path / "private-pin"
    write_pin(path)
    async def check():
        async with unlock_application(tmp_path, path) as (app, client):
            runtime = app[RUNTIME]
            assert runtime.unlock_pin_file == path
            assert "/unlock" not in POST_PATHS
            routes = [route for route in app.router.routes() if route.resource.canonical == "/unlock"]
            assert len(routes) == 1 and routes[0].method == "POST"
            session = runtime.sessions.create_session("Synthetic Phone", "synthetic", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            await phone.status()
            for length, status, result in (
                (5, 200, {"success": True, "unlocked": True}),
                (7, 423, {"success": False, "code": "keyguard_blocked", "error": "PIN not accepted.",
                          "stage": "pin_not_accepted", "pin_attempt_used": True}),
                (8, 200, {"success": True, "unlocked": True, "already_unlocked": True}),
            ):
                pin = write_pin(path, length)
                query = {"device": "synthetic", "device_id": "discard", "extra": "kept"}
                body = {"deviceId": "discard", "ignored": "not forwarded"}
                if selector_source == "body":
                    query = {"extra": "kept"}
                    body["deviceId"] = "synthetic"
                task = asyncio.create_task(client.post("/unlock", params=query, json=body))
                command = await phone.receive()
                payload = command["payload"]
                matched = payload["body"] == {"pin": pin}
                assert matched, "Command body differs (contents redacted)"
                matched = payload["params"] == {"extra": "kept"}
                assert matched, "Command params differ (contents redacted)"
                assert payload["method"] == "POST" and payload["path"] == "/unlock"
                await phone.respond(command, status=status, result=result)
                response = await asyncio.wait_for(task, timeout=2)
                matched = response.status == status and await response.json() == result
                assert matched, "Phone response changed (contents redacted)"
                await phone.sync()
            await phone.close()
    with caplog.at_level(logging.DEBUG, logger="atomic_relay"):
        asyncio.run(check())


@pytest.mark.parametrize(("failure", "reason"), [
    ("missing", "file unavailable"), ("public", "private file permissions required"),
    ("non-digit", "digits required"), ("short", "digits required"), ("long", "digits required"),
    ("symlink", "file unavailable"), ("foreign-owner", "current user ownership required"),
    ("oversized", "file too large"),
])
def test_changed_file_refused_with_safe_warning_before_dispatch(tmp_path, monkeypatch, failure, reason, caplog):
    path = tmp_path / "private-pin"
    write_pin(path)
    async def check():
        async with unlock_application(tmp_path, path) as (app, client):
            sent = []
            async def unexpected(*args):
                sent.append(True)
                raise AssertionError("A refused request reached dispatch")
            app[RUNTIME].dispatch.command = unexpected
            if failure == "missing":
                path.unlink()
            else:
                content = write_pin(path, {"short": 3, "long": 17}.get(failure, 6))
                if failure == "public":
                    path.chmod(0o644)
                elif failure == "non-digit":
                    path.write_text(path.read_text() + "x")
                elif failure == "symlink":
                    target = tmp_path / "private-target"
                    path.rename(target)
                    path.symlink_to(target)
                elif failure == "oversized":
                    path.write_text(" " * 1024 + content)
                elif failure == "foreign-owner":
                    original, inode = os.fstat, path.stat().st_ino
                    def foreign_owner(descriptor):
                        metadata = original(descriptor)
                        if metadata.st_ino != inode:
                            return metadata
                        return SimpleNamespace(st_mode=metadata.st_mode, st_size=metadata.st_size,
                                               st_uid=os.geteuid() + 1)
                    monkeypatch.setattr(os, "fstat", foreign_owner)
            response = await client.post("/unlock", json={})
            matched = response.status == 503 and await response.json() == {"error": "unlock PIN unavailable"}
            assert matched, "Unexpected file refusal (contents redacted)"
            assert not sent
    with caplog.at_level(logging.WARNING):
        asyncio.run(check())
    messages = [row.getMessage() for row in caplog.records if row.name == "atomic_relay.unlock"]
    matched = messages == ["component=unlock operation=read reason=" + reason]
    assert matched, "Unexpected warning (contents redacted)"
    assert all(not character.isdigit() for message in messages for character in message)


@pytest.mark.parametrize("source", ["query", "body"])
def test_client_pin_is_refused_without_sending_phone_command(tmp_path, source):
    path = tmp_path / "private-pin"
    pin = write_pin(path)
    async def check():
        async with unlock_application(tmp_path, path) as (app, client):
            session = app[RUNTIME].sessions.create_session("Synthetic Phone", "synthetic", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            await phone.status()
            kwargs = {"params": {"pin": pin}, "json": {}} if source == "query" else {"json": {"pin": pin}}
            response = await client.post("/unlock", **kwargs)
            matched = response.status == 400 and await response.json() == {"error": "pin is supplied by the relay"}
            assert matched, "Unexpected client-PIN refusal (contents redacted)"
            # A queued command would arrive before the matching pong and fail sync.
            await phone.sync()
            assert not app[RUNTIME].dispatch.pending
            await phone.close()
    asyncio.run(check())


@pytest.mark.parametrize(("headers", "status", "message"), [
    ({"X-Forwarded-For": "synthetic"}, 403, "loopback only"),
    ({"Origin": "https://synthetic.invalid"}, 403, "browser origin refused"),
    ({"Content-Type": "text/plain"}, 415, "application/json required"),
    ({"Authorization": ""}, 401, "admin credential required"),
])
def test_unlock_uses_shared_guard_without_ok_field(tmp_path, headers, status, message):
    async def check():
        async with application(tmp_path, admin=True) as (_, client):
            response = await client.post("/unlock", headers=headers, json={})
            assert response.status == status and await response.json() == {"error": message}
    asyncio.run(check())


def test_unlock_refuses_a_non_loopback_transport(tmp_path):
    app = create_app(configuration(tmp_path))
    transport = Mock()
    transport.get_extra_info.return_value = ("192.0.2.1", 1)
    request = make_mocked_request("POST", "/unlock", app=app, transport=transport)
    async def unexpected(request):
        raise AssertionError("Non-loopback request reached the route")
    response = asyncio.run(admin_middleware({"/unlock"})(request, unexpected))
    assert response.status == 403 and json.loads(response.body) == {"error": "loopback only"}


@pytest.mark.parametrize("body", [[], {"device": 42}, "{invalid"])
def test_invalid_body_and_selector_have_fixed_refusal(tmp_path, body):
    async def check():
        async with application(tmp_path, admin=True) as (_, client):
            kwargs = {"data": body} if isinstance(body, str) else {"json": body}
            response = await client.post("/unlock", **kwargs)
            assert response.status == 400 and await response.json() == {"error": "invalid command body or selector"}
    asyncio.run(check())


def test_configured_unlock_without_phone_uses_bridge_error(tmp_path):
    path = tmp_path / "private-pin"
    write_pin(path)
    async def check():
        async with unlock_application(tmp_path, path) as (_, client):
            response = await client.post("/unlock", json={})
            assert response.status == 503 and await response.json() == {"error": "no phone connected"}
    asyncio.run(check())
