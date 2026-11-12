# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import logging

from atomic_relay.web.runtime import RUNTIME
from fake_phone import FakePhone, session_payload
from session_cases import application


def test_unknown_channel_and_type_reply_once_then_valid_ping_survives(tmp_path, caplog, monkeypatch):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    async def check():
        async with application(tmp_path) as (app, client):
            session = app[RUNTIME].sessions.create_session("Synthetic", "device", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            for channel in ("unknown-private-channel", "bridge", "system", "notifications"):
                await phone.send(channel, "unknown-private-type", {})
                assert (await phone.receive())["payload"] == {"message": "Unknown channel"}
                await phone.send(channel, "unknown-private-type", {})
                await phone.sync()
            await phone.close()
    with caplog.at_level(logging.DEBUG):
        asyncio.run(check())
    assert "unknown-private-channel" not in caplog.text and "unknown-private-type" not in caplog.text
    assert len([row for row in caplog.records if row.message == "component=websocket operation=dispatch unknown channel or type"]) == 4


def test_expired_bridge_grant_refuses_status_and_responses(tmp_path):
    async def check():
        async with application(tmp_path) as (app, client):
            runtime = app[RUNTIME]
            session = runtime.sessions.create_session("Synthetic", "device", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            session.grants["bridge"] = 1
            for kind in ("bridge.status", "bridge.response"):
                await phone.send("bridge", kind, {})
                assert (await phone.receive())["payload"] == {"message": "Bridge grant expired for this device"}
            assert not runtime.registry.is_phone_connected()
            assert (await client.get("/ping", headers={"Authorization": f"Bearer {session.token}"})).status == 401
            await phone.close()
    asyncio.run(check())


def test_bad_message_and_dependency_failure_are_private_and_connection_recovers(tmp_path, monkeypatch, caplog):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    private = "synthetic-private-exception-value"
    async def check():
        async with application(tmp_path) as (app, client):
            runtime = app[RUNTIME]
            session = runtime.sessions.create_session("Synthetic", "device", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            await asyncio.wait_for(phone.socket.send_str("invalid-" + private), timeout=2)
            assert (await phone.receive())["payload"] == {"message": "Invalid message"}
            original = runtime.dispatch.response
            def failed(*args):
                raise RuntimeError(private)
            monkeypatch.setattr(runtime.dispatch, "response", failed)
            await phone.send("bridge", "bridge.response", {})
            assert (await phone.receive())["payload"] == {"message": "Invalid message"}
            monkeypatch.setattr(runtime.dispatch, "response", original)
            await phone.sync()
            await phone.close()
    with caplog.at_level(logging.ERROR):
        asyncio.run(check())
    assert private not in caplog.text
    assert len([row for row in caplog.records if row.exc_info]) == 2
