# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import json
from types import SimpleNamespace

from atomic_relay.web.runtime import RUNTIME
from session_cases import admin_headers, application
from atomic_relay.sessions import manager


def test_list_prefix_only_admin_required_and_never_current(tmp_path, caplog):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            manager = app[RUNTIME].sessions
            session = manager.create_session("phone", "device", ttl_seconds=0)
            assert (await client.get("/sessions", headers={"Authorization": ""})).status == 401
            response = await client.get("/sessions")
            assert response.status == 200
            body = await response.json()
            assert body["sessions"][0]["token_prefix"] == session.token[:8]
            assert session.token not in json.dumps(body)
            assert body["sessions"][0]["is_current"] is False
            assert (await client.get("/sessions", headers={"Forwarded": "synthetic"})).status == 403
            auth = {"Authorization": f"Bearer {session.token}"}
            response = await client.get("/sessions", headers=auth)
            assert response.status == 401
            assert (await client.get("/sessions", headers={"Authorization": "Bearer synthetic-invalid"})).status == 401
            session.grants["bridge"] = 1
            assert (await client.get("/sessions", headers=auth)).status == 401
            assert session.token not in caplog.text and admin_headers(app)["Authorization"][7:] not in caplog.text
    asyncio.run(check())


def test_delete_requires_bearer_revokes_trust_and_closes_only_owned_sockets(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            runtime = app[RUNTIME]
            caller = runtime.sessions.create_session("caller", "caller", ttl_seconds=0)
            target = runtime.sessions.create_session("target", "target", ttl_seconds=0, issue_refresh_token=True)
            class Socket:
                closed = False
                async def close(self, **kwargs):
                    self.closed = True
                    self.kwargs = kwargs
            own, other = Socket(), Socket()
            runtime.clients.update({own: target.token, other: caller.token})
            route = f"/sessions/{target.token[:8]}"
            assert (await client.delete(route, headers={"Authorization": ""})).status == 401
            auth = admin_headers(app)
            response = await client.delete(route, headers=auth)
            assert response.status == 200
            assert await response.json() == {"ok": True, "revoked": target.token[:8], "revoked_self": False}
            assert own.closed and not other.closed
            assert own.kwargs["code"] == 1008 and not runtime.sessions.has_trusted_device("target")
            assert (await client.delete(route, headers=auth)).status == 404
            response = await client.delete(f"/sessions/{caller.token[:8]}", headers=auth)
            assert (await response.json())["revoked_self"] is False
    asyncio.run(check())


def test_patch_is_atomic_reduce_only_and_admin_can_target_any_session(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            sessions = app[RUNTIME].sessions
            caller = sessions.create_session("caller", "caller", ttl_seconds=1000)
            other = sessions.create_session("other", "other", ttl_seconds=1000)
            auth = admin_headers(app)
            path = f"/sessions/{caller.token[:8]}"
            assert (await client.patch(path, headers={"Authorization": ""}, json={"ttl_seconds": 10})).status == 401
            assert (await client.patch(f"/sessions/{other.token[:8]}", headers=auth, json={"ttl_seconds": 900})).status == 200
            for body in ({}, [], {"ttl_seconds": True}, {"ttl_seconds": -1}, {"grants": []}, {"grants": {"bridge": True}}):
                assert (await client.patch(path, headers=auth, json=body)).status == 400
            for body in ({"ttl_seconds": 0}, {"grants": {"bridge": 0}}):
                assert (await client.patch(path, headers=auth, json=body)).status == 403
            assert (await client.patch(path, headers=auth, json={"grants": {"unknown": 1}})).status == 400
            response = await client.patch(path, headers=auth, json={"ttl_seconds": 100, "grants": {"bridge": 50}})
            assert response.status == 200
            body = await response.json()
            assert body["ok"] and body["grants"]["bridge"] < body["expires_at"]
            assert sessions.list_sessions()[0].expires_at < other.expires_at
    asyncio.run(check())


def test_prefix_collision_and_empty_prefix_refuse_mutation(tmp_path, monkeypatch):
    values = iter(["sameprefix-first-synthetic", "sameprefix-second-synthetic"])
    monkeypatch.setattr(manager, "uuid", SimpleNamespace(uuid4=lambda: next(values)))
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            sessions = app[RUNTIME].sessions
            caller = sessions.create_session("caller", "caller", ttl_seconds=0)
            sessions.create_session("other", "other", ttl_seconds=0)
            auth = admin_headers(app)
            for method in (client.delete, client.patch):
                kwargs = {"json": {"ttl_seconds": 10}} if method == client.patch else {}
                assert (await method("/sessions/sameprefix", headers=auth, **kwargs)).status == 409
                assert (await method("/sessions/%20", headers=auth, **kwargs)).status == 400
                short = await method("/sessions/1234567", headers=auth, **kwargs)
                assert short.status == 400
                assert await short.json() == {"error": "token prefix too short"}
            assert sessions.active_count() == 2
    asyncio.run(check())
