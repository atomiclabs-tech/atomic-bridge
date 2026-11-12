# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import logging
from types import SimpleNamespace

import pytest

from atomic_relay.config import DEFAULTS, load_config
from atomic_relay.web.runtime import RUNTIME
from fake_phone import FakePhone, session_payload
from notification_cases import posted, removed
from session_cases import application, configuration


@pytest.mark.parametrize("target", ["recent", "waiter"])
def test_attack_denied_package_reaches_neither_recent_nor_waiters(tmp_path, monkeypatch, caplog, target):
    blocked, allowed, private = "com.example.bank", "com.example.other", "synthetic-private-filter-content"
    config = configuration(tmp_path)
    if "ATOMIC_RELAY_NOTIFY_DENY" in DEFAULTS:
        config = load_config(environ={
            "ATOMIC_RELAY_PORT": "0", "ATOMIC_RELAY_SESSIONS_FILE": "",
            "ATOMIC_RELAY_ADMIN_SECRET_FILE": str(tmp_path / "admin.secret"),
            "ATOMIC_RELAY_LOG_FILE": str(tmp_path / "relay.log"),
            "ATOMIC_RELAY_MEDIA_DIR": str(tmp_path / "media"), "ATOMIC_RELAY_NOTIFY_DENY": blocked,
        })
    else:
        # The old runtime ignores future settings; its buffering/publication stays unchanged.
        config = SimpleNamespace(**vars(config), notification_allow=(), notification_deny=(blocked,))
    monkeypatch.setattr("session_cases.configuration", lambda *args: config)
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            runtime = app[RUNTIME]
            session = runtime.sessions.create_session("Synthetic", "filter-device", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            waiter = asyncio.create_task(runtime.notification_waiters.wait(timeout_s=1, since=0))
            await asyncio.sleep(0)
            assert len(runtime.notification_waiters.pending) == 1
            try:
                await phone.send("notifications", "notification.posted", posted(package_name=blocked, text=private))
                await phone.send("notifications", "notification.removed", removed(package_name=blocked))
                await phone.sync()
                response = await client.get("/notifications/recent")
                status = response.status
                assert status == 200
                result = await response.json()
                if target == "recent":
                    absent = result["count"] == 0
                    assert absent
                else:
                    unpublished = not waiter.done() and all(row["result"] is None for row in runtime.notification_waiters.pending.values())
                    assert unpublished
                await phone.send("notifications", "notification.posted", posted("kept", 102, package_name=allowed, text=private))
                await phone.send("notifications", "notification.removed", removed("kept", 103, package_name=allowed))
                await phone.sync()
                delivered = await asyncio.wait_for(waiter, timeout=2)
                correct = delivered["matched"] and delivered["notification"]["package_name"] == allowed
                assert correct
                response = await client.get("/notifications/recent")
                result = await response.json()
                correct = result["count"] == 2 and all(row["package_name"] == allowed for row in result["notifications"])
                assert correct
            finally:
                runtime.notification_waiters.close()
                await asyncio.wait_for(waiter, timeout=2)
                await phone.close()
    with caplog.at_level(logging.DEBUG):
        asyncio.run(check())
    drops = [row for row in caplog.records if row.message == "component=notifications operation=filter dropped"]
    assert len(drops) == 2 and all(row.levelno == logging.DEBUG for row in drops)
    private_absent = all(value not in caplog.text for value in (blocked, allowed, private))
    assert private_absent


def test_actual_posted_nullable_removed_frames_use_session_device_and_never_log_content(tmp_path, caplog, monkeypatch):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    private = "synthetic-private-notification-content"
    async def check():
        async with application(tmp_path) as (app, client):
            runtime = app[RUNTIME]
            session = runtime.sessions.create_session("Pixel Synthetic", "authoritative-device", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            payload = posted(text=private, title=None, sub_text=None, device_id="spoofed")
            await phone.send("notifications", "notification.posted", payload)
            await phone.send("notifications", "notification.posted", payload)
            await phone.send("notifications", "notification.removed", removed(reason=2))
            await phone.sync()
            events = runtime.notifications.recent()
            assert len(events) == 2 and all(row["device_id"] == session.device_id for row in events)
            assert "posted_at" not in events[0] and events[0]["removed_at"] == 101
            assert events[1]["title"] is None and events[1]["sub_text"] is None
            session.grants["bridge"] = 1
            await phone.send("notifications", "notification.posted", posted("ignored", 102))
            await phone.sync()
            assert len(runtime.notifications.recent()) == 2
            await phone.close()
            assert runtime.notifications.recent(text=private)
    with caplog.at_level(logging.DEBUG):
        asyncio.run(check())
    assert private not in caplog.text and "synthetic-key" not in caplog.text


def test_unauthenticated_frame_and_malformed_notification_do_not_enter_buffer(tmp_path):
    async def check():
        async with application(tmp_path) as (app, client):
            runtime = app[RUNTIME]
            socket = await asyncio.wait_for(client.ws_connect("/ws"), timeout=2)
            await asyncio.wait_for(socket.send_json({"channel": "notifications", "type": "notification.posted", "payload": posted()}), timeout=2)
            assert (await asyncio.wait_for(socket.receive_json(), timeout=2))["type"] == "auth.fail"
            await asyncio.wait_for(socket.close(), timeout=2)
            assert runtime.notifications.recent() == []
            session = runtime.sessions.create_session("Synthetic", "device", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            await phone.send("notifications", "notification.posted", {"key": "missing-fields"})
            assert (await phone.receive())["payload"] == {"message": "Invalid message"}
            await phone.sync()
            assert runtime.notifications.recent() == []
            await phone.close()
    asyncio.run(check())
