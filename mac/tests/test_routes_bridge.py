# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import logging

import pytest

from atomic_relay.web.routes_bridge import GET_PATHS, POST_PATHS
from atomic_relay.web.runtime import RUNTIME
from fake_phone import FakePhone, session_payload
from session_cases import admin_headers, application


async def connected(app, client, identity="device", name="Pixel Synthetic"):
    session = app[RUNTIME].sessions.create_session(name, identity, ttl_seconds=0)
    phone = await FakePhone.connect(client, session_payload(session))
    return session, phone, {**admin_headers(app), "Content-Type": "application/json"}


def test_before_status_unavailable_then_get_post_roundtrip_preserve_phone_result(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            session, phone, auth = await connected(app, client)
            assert (await client.get("/ping", headers=auth)).status == 503
            device = {"display_name": "Synthetic", "extra": {"unchanged": True}}
            await phone.status({"battery": 50, "device": device})
            task = asyncio.create_task(client.get("/ping?device=phone&device_id=discard&deviceId=discard&limit=2&include_bounds=true", headers=auth))
            command = await phone.receive()
            assert command["payload"]["method"] == "GET" and command["payload"]["path"] == "/ping"
            assert command["payload"]["params"] == command["payload"]["body"] == {"limit": "2", "include_bounds": "true"}
            result = {"success": False, "error": "synthetic phone refusal", "code": "permission_denied"}
            await phone.respond(command, status=418, result=result)
            response = await asyncio.wait_for(task, timeout=2)
            assert response.status == 418 and await response.json() == result
            body = {"deviceId": "phone", "device": "phone", "device_id": "phone", "nested": {"device": "keep"}, "text": "synthetic"}
            task = asyncio.create_task(client.post("/type?device=phone&extra=query", headers=auth, json=body))
            command = await phone.receive()
            assert command["payload"]["body"] == {"nested": {"device": "keep"}, "text": "synthetic"}
            assert command["payload"]["params"] == {"extra": "query"}
            await phone.respond(command, status=207, result=[{"success": True}])
            response = await asyncio.wait_for(task, timeout=2)
            assert response.status == 207 and await response.json() == [{"success": True}]
            status = await (await client.get("/bridge/status")).json()
            assert status["device"] == device and status["device_id"] == session.device_id
            assert status["session_token_prefix"] == session.token[:8] and status["phone_connected"]
            devices = await (await client.get("/bridge/devices")).json()
            assert devices == {"active_device_id": "device", "most_recent_device_id": "device", "devices": [status], "count": 1}
            await phone.close()
    asyncio.run(check())


def test_route_inventory_every_legacy_and_additive_path_forwards_without_renaming(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            _, phone, auth = await connected(app, client)
            await phone.status()
            for method, paths in (("GET", GET_PATHS), ("POST", POST_PATHS)):
                for path in paths:
                    kwargs = {"json": {"number": 1}} if method == "POST" else {}
                    task = asyncio.create_task(client.request(method, path, headers=auth, **kwargs))
                    command = await phone.receive()
                    assert command["payload"]["path"] == path and command["payload"]["method"] == method
                    await phone.respond(command, result="unchanged scalar")
                    response = await asyncio.wait_for(task, timeout=2)
                    assert response.status == 200 and await response.json() == "unchanged scalar"
            await phone.close()
    asyncio.run(check())


@pytest.mark.parametrize("header", ["X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "Forwarded", "X-Real-IP"])
def test_all_bridge_management_routes_use_shared_loopback_guard(tmp_path, header):
    async def check():
        async with application(tmp_path, admin=True) as (_, client):
            for method, path in (("GET", "/bridge/status"), ("GET", "/bridge/devices"), ("POST", "/bridge/select-active")):
                response = await client.request(method, path, headers={header: "synthetic"})
                assert response.status == 403 and (await response.json())["error"] == "loopback only"
            response = await client.get("/bridge/status")
            assert response.status == 503 and await response.json() == {"phone_connected": False, "last_seen_seconds_ago": None,
                                                                        "error": "no phone connected"}
    asyncio.run(check())


def test_authorization_bad_input_selectors_and_select_active(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            session, one, auth = await connected(app, client, "one", "Pixel One")
            _, two, _ = await connected(app, client, "two", "Pixel Two")
            await one.status()
            await two.status()
            assert (await client.get("/ping", headers={"Authorization": ""})).status == 401
            assert (await client.get("/ping", headers={"Authorization": "Bearer synthetic-invalid"})).status == 401
            for selector, code in (("missing", 404), ("phone", 409)):
                for path in ("/ping", "/bridge/status"):
                    assert (await client.get(path, params={"device": selector}, headers=auth)).status == code
                assert (await client.post("/bridge/select-active", json={"device": selector})).status == code
            for body in ({}, [], {"device": 3}):
                assert (await client.post("/bridge/select-active", json=body)).status == 400
            assert (await client.post("/bridge/select-active", data="{invalid")).status == 400
            selected = await client.post("/bridge/select-active", json={"deviceId": "Pixel Two"})
            assert selected.status == 200 and (await selected.json())["active_device_id"] == "two"
            task = asyncio.create_task(client.get("/ping", headers=auth))
            command = await two.receive()
            await two.respond(command)
            assert (await task).status == 200
            for body in ([], {"device": 42}):
                assert (await client.post("/tap", headers=auth, json=body)).status == 400
            assert (await client.post("/tap", headers=auth, data="{invalid")).status == 400
            session.grants["bridge"] = 1
            assert (await client.get("/ping", headers={"Authorization": "Bearer " + session.token})).status == 401
            await one.close()
            await two.close()
    asyncio.run(check())


def test_command_timeout_disconnect_and_reconnect_fail_pending_once(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            runtime = app[RUNTIME]
            session, phone, auth = await connected(app, client)
            await phone.status()
            runtime.dispatch.timeout = 0.001
            response = await client.get("/ping", headers=auth)
            assert response.status == 504 and runtime.dispatch.pending == {}
            await phone.receive()
            runtime.dispatch.timeout = 2
            task = asyncio.create_task(client.get("/ping", headers=auth))
            await phone.receive()
            await phone.close()
            assert (await asyncio.wait_for(task, timeout=2)).status == 502
            assert not runtime.registry.is_phone_connected()
            assert not (await (await client.get("/bridge/status")).json())["phone_connected"]
            old = await FakePhone.connect(client, session_payload(session))
            await old.status()
            task = asyncio.create_task(client.get("/ping", headers=auth))
            old_command = await old.receive()
            new = await FakePhone.connect(client, session_payload(session))
            await new.status()
            assert (await asyncio.wait_for(task, timeout=2)).status == 502
            await old.close()
            assert runtime.registry.is_phone_connected() and runtime.registry.devices_payload()["count"] == 1
            await new.respond(old_command)
            await new.sync()
            assert runtime.dispatch.pending == {}
            await new.close()
    asyncio.run(check())


def test_wrong_device_cannot_complete_a_command_and_revocation_detaches(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            session, one, auth = await connected(app, client, "one")
            _, two, _ = await connected(app, client, "two")
            await one.status()
            await two.status()
            task = asyncio.create_task(client.get("/ping?device=one", headers=auth))
            command = await one.receive()
            await two.respond(command, result={"wrong": True})
            await two.sync()
            assert not task.done()
            await one.respond(command, result={"correct": True})
            assert await (await asyncio.wait_for(task, timeout=2)).json() == {"correct": True}
            response = await client.delete(f"/sessions/{session.token[:8]}", headers=auth)
            assert response.status == 200
            await one.close()
            devices = await (await client.get("/bridge/devices")).json()
            assert devices["count"] == 1 and devices["active_device_id"] == "two"
            await two.close()
    asyncio.run(check())


def test_send_and_http_dependency_failures_do_not_leak_private_exception_or_stop_server(tmp_path, caplog, monkeypatch):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    private = "synthetic-private-route-detail"
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            _, phone, auth = await connected(app, client)
            await phone.status()
            socket = app[RUNTIME].registry.resolve()[0].socket
            original = socket.send_str
            async def failed(*args):
                raise OSError(private)
            monkeypatch.setattr(socket, "send_str", failed)
            response = await client.post("/type", headers=auth, json={"text": private})
            assert response.status == 502
            assert private not in await response.text()
            monkeypatch.setattr(socket, "send_str", original)
            def registry_failed():
                raise RuntimeError(private)
            monkeypatch.setattr(app[RUNTIME].registry, "devices_payload", registry_failed)
            response = await client.get("/bridge/devices")
            assert response.status == 500 and private not in await response.text()
            assert (await client.get("/health")).status == 200
            await phone.sync()
            await phone.close()
    with caplog.at_level(logging.ERROR):
        asyncio.run(check())
    assert private not in caplog.text
    assert len([row for row in caplog.records if row.exc_info]) >= 2
