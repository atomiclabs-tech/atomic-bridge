# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import time

import pytest

from atomic_relay.web.runtime import RUNTIME
from fake_phone import FakePhone, session_payload
from notification_cases import posted, removed
from session_cases import application


def test_recent_query_filters_nullable_values_and_retained_disconnected_device(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            runtime = app[RUNTIME]
            session = runtime.sessions.create_session("Pixel Synthetic", "retained-device", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            await phone.send("notifications", "notification.posted", posted("null", 100, title=None, text=None, sub_text=None))
            await phone.send("notifications", "notification.posted", posted("matching", 101, sub_text="Mixed Match"))
            await phone.send("notifications", "notification.removed", removed("matching", 102))
            await phone.sync()
            await phone.close()
            response = await client.get("/notifications/recent", params={"device": "Pixel Synthetic", "package": "synthetic.package",
                                                                         "text": "MIXED match", "since": "100", "limit": "1"})
            result = await response.json()
            assert response.status == 200 and result["ok"] and result["count"] == 1
            assert result["notifications"][0]["key"] == "matching"
            response = await client.get("/notifications/recent")
            assert (await response.json())["count"] == 3
            response = await client.get("/notifications/recent", params={"since": "100"})
            result = await response.json()
            assert response.status == 200 and result["count"] == 2
            assert [row["type"] for row in result["notifications"]] == ["notification.removed", "notification.posted"]
            assert result["notifications"][0]["removed_at"] == 102
            response = await client.get("/notifications/recent", params={"since": "102"})
            assert (await response.json())["notifications"] == []
    asyncio.run(check())


def test_concurrent_http_waiters_receive_first_event_and_default_since_ignores_history(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            runtime = app[RUNTIME]
            runtime.notifications.append("device", "notification.posted", posted("old", 1))
            requests = [asyncio.create_task(client.get("/notifications/wait", params={"text": "MATCH", "timeout_s": "1"})) for _ in range(3)]
            async def ready():
                while len(runtime.notification_waiters.pending) < 3:
                    await asyncio.sleep(0)
            await asyncio.wait_for(ready(), timeout=2)
            session = runtime.sessions.create_session("Synthetic", "device", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            now = int(time.time() * 1000) + 100
            await phone.send("notifications", "notification.removed", removed(at=now))
            await phone.send("notifications", "notification.posted", posted("first", now, text="Match"))
            await phone.send("notifications", "notification.posted", posted("second", now + 1, text="Match"))
            results = [await response.json() for response in await asyncio.wait_for(asyncio.gather(*requests), timeout=2)]
            assert all(row["matched"] and row["notification"]["key"] == "first" for row in results)
            assert runtime.notification_waiters.pending == {}
            await phone.close()
    asyncio.run(check())


def test_wait_timeout_is_http_200_and_query_ranges_are_checked(tmp_path, monkeypatch):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            runtime = app[RUNTIME]
            seen, original = [], asyncio.wait_for
            async def short(awaitable, *, timeout):
                seen.append(timeout)
                return await original(awaitable, timeout=0.001)
            monkeypatch.setattr(runtime.notification_waiters, "wait_for", short)
            response = await client.get("/notifications/wait?timeout_s=1&since=0")
            result = await response.json()
            assert response.status == 200 and result["ok"] and not result["matched"] and result["waited_s"] >= 0
            assert seen == [1]
            assert (await client.get("/notifications/wait")).status == 200
            assert (await client.get("/notifications/wait?timeout_s=120")).status == 200
            assert seen == [1, 60, 120]
            for path in ("/notifications/recent?limit=0", "/notifications/recent?limit=501", "/notifications/recent?limit=bad",
                         "/notifications/recent?since=NaN", "/notifications/wait?timeout_s=0", "/notifications/wait?timeout_s=121",
                         "/notifications/wait?since=-1"):
                response = await client.get(path)
                assert response.status == 400
                assert await response.json() == {"ok": False, "error": "invalid notification query"}
            assert (await client.get("/notifications/recent?device=missing")).status == 404
    asyncio.run(check())


@pytest.mark.parametrize("header", ["X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "Forwarded", "X-Real-IP"])
def test_notification_routes_use_shared_loopback_guard(tmp_path, header):
    async def check():
        async with application(tmp_path, admin=True) as (_, client):
            for path in ("/notifications/recent", "/notifications/wait"):
                response = await client.get(path, headers={header: "synthetic"})
                assert response.status == 403 and await response.json() == {"ok": False, "error": "loopback only"}
    asyncio.run(check())
