# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import logging
import secrets
import string

import pytest

from atomic_relay.web.runtime import RUNTIME
from session_cases import application


@pytest.mark.parametrize("path", ["/pairing/register", "/pairing/approve"])
def test_registration_loopback_guard_full_alphabet_and_preserve_blocks(tmp_path, caplog, monkeypatch, path):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            runtime = app[RUNTIME]
            for _ in range(5):
                runtime.rate_limiter.record_session_failure("phone-client")
            synthetic = "IO01AZ"
            with caplog.at_level(logging.DEBUG):
                denied = await client.post(path, json={"code": synthetic}, headers={"X-Forwarded-For": "private-address"})
                assert denied.status == 403
                assert runtime.rate_limiter.is_blocked("phone-client")
                response = await client.post(path, json={"code": synthetic, "ttl_seconds": 0, "grants": {"bridge": 0}})
                assert response.status == 200 and await response.json() == {"ok": True, "code": synthetic}
            assert runtime.rate_limiter.is_blocked("phone-client")
            assert runtime.pairing.consume_code(synthetic).ttl_seconds == 0
            assert synthetic not in caplog.text and "private-address" not in caplog.text
    asyncio.run(check())


def test_pairing_check_pending_consumed_and_expired(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            manager = app[RUNTIME].pairing
            now = [0.0]
            manager.clock = lambda: now[0]
            code = "".join(secrets.choice(string.ascii_uppercase) for _ in range(6))
            registered = manager.register_code(code)
            assert registered
            response = await client.post("/pairing/check", json={"code": code})
            assert response.status == 200 and await response.json() == {"ok": True, "pending": True}
            consumed = manager.consume_code(code)
            assert consumed is not None
            response = await client.post("/pairing/check", json={"code": code})
            assert response.status == 200 and await response.json() == {"ok": True, "pending": False}
            registered = manager.register_code(code)
            assert registered
            now[0] = 601
            response = await client.post("/pairing/check", json={"code": code})
            assert response.status == 200 and await response.json() == {"ok": True, "pending": False}
    asyncio.run(check())


@pytest.mark.parametrize("case", ["bad-json", "array", "missing", "invalid", "numeric"])
def test_pairing_check_malformed_body_is_400(tmp_path, case):
    async def check():
        async with application(tmp_path, admin=True) as (_, client):
            bodies = {"array": [], "missing": {}, "invalid": {"code": "short"}, "numeric": {"code": 1}}
            response = await client.post("/pairing/check", data="{bad" if case == "bad-json" else None,
                                         json=bodies.get(case) if case != "bad-json" else None)
            assert response.status == 400
            assert await response.json() == {"ok": False, "error": "invalid pairing request"}
    asyncio.run(check())


@pytest.mark.parametrize("headers,status,error", [
    ({}, 401, "admin credential required"),
    ({"Origin": "https://browser.example"}, 403, "browser origin refused"),
    ({"X-Forwarded-For": "synthetic-peer"}, 403, "loopback only"),
])
def test_pairing_check_uses_existing_admin_guard(tmp_path, headers, status, error):
    async def check():
        async with application(tmp_path) as (_, client):
            code = "".join(secrets.choice(string.ascii_uppercase) for _ in range(6))
            response = await client.post("/pairing/check", json={"code": code}, headers=headers)
            assert response.status == status
            assert await response.json() == {"ok": False, "error": error}
    asyncio.run(check())


@pytest.mark.parametrize("body", [None, [], {}, {"code": 123456}, {"code": "BAD"},
                                 {"code": "ABC123", "ttl_seconds": True}, {"code": "ABC123", "grants": []},
                                 {"code": "ABC123", "transport_hint": "bad"}])
def test_bad_pairing_requests_are_400(tmp_path, body):
    async def check():
        async with application(tmp_path, admin=True) as (_, client):
            response = await client.post("/pairing/register", data="{bad" if body is None else None,
                                         json=body if body is not None else None)
            assert response.status == 400 and (await response.json())["ok"] is False
    asyncio.run(check())
