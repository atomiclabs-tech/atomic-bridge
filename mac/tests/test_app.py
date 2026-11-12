# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import secrets

from aiohttp import ClientTimeout
from aiohttp.test_utils import TestClient, TestServer

from atomic_relay.config import load_config
from atomic_relay.web.app import create_app
from session_cases import FIXTURE, admin_headers, configuration


def test_health_get_head_and_route_scope(tmp_path):
    sessions = tmp_path / "sessions.json"
    sessions.write_bytes(FIXTURE.read_bytes())
    configuration(tmp_path)
    config = load_config(environ={
        "ATOMIC_RELAY_PORT": "0", "ATOMIC_RELAY_SESSIONS_FILE": str(sessions),
        "ATOMIC_RELAY_LOG_FILE": str(tmp_path / "relay.log"),
        "ATOMIC_RELAY_MEDIA_DIR": str(tmp_path / "media"), "ATOMIC_RELAY_SERVER_VERSION": "test-version",
        "ATOMIC_RELAY_ADMIN_SECRET_FILE": str(tmp_path / "admin.secret"),
    })
    now = [50.0]
    async def check():
        client = TestClient(TestServer(create_app(config, clock=lambda: now[0]), port=0), timeout=ClientTimeout(total=2))
        await asyncio.wait_for(client.start_server(), timeout=2)
        try:
            now[0] = 52.5
            response = await client.get("/health")
            assert response.status == 200
            assert await response.json() == {
                "status": "ok", "version": "test-version", "uptime_seconds": 2.5,
            }
            public_keys = {"status", "version", "uptime_seconds"}
            assert set(await (await client.get("/health", headers={"Authorization": "Bearer " + secrets.token_urlsafe(32)})).json()) == public_keys
            detailed = await (await client.get("/health", headers=admin_headers(client.app))).json()
            assert set(detailed) == public_keys | {"clients", "sessions", "phone_connected"}
            assert detailed["clients"] == 0 and detailed["sessions"] == 1 and not detailed["phone_connected"]
            response = await client.head("/health")
            assert response.status == 200
            assert await response.read() == b""
            assert (await client.post("/health")).status == 405
            assert (await client.get("/ws")).status == 400
        finally:
            await asyncio.wait_for(client.close(), timeout=2)
    asyncio.run(check())


def test_ephemeral_admin_routes_refuse_every_caller(tmp_path, caplog):
    config = load_config(environ={
        "ATOMIC_RELAY_PORT": "0", "ATOMIC_RELAY_SESSIONS_FILE": "", "ATOMIC_RELAY_ADMIN_SECRET_FILE": "",
        "ATOMIC_RELAY_LOG_FILE": str(tmp_path / "relay.log"), "ATOMIC_RELAY_MEDIA_DIR": str(tmp_path / "media"),
    })
    async def check():
        async with TestClient(TestServer(create_app(config), port=0), timeout=ClientTimeout(total=2)) as client:
            for header in ({}, {"Authorization": "Bearer " + secrets.token_urlsafe(32)}):
                response = await client.get("/sessions", headers=header)
                assert response.status == 401
                assert await response.json() == {"error": "admin credential required"}
                assert header.get("Authorization", "unused") not in caplog.text
            assert set(await (await client.get("/health")).json()) == {"status", "version", "uptime_seconds"}
    asyncio.run(check())
