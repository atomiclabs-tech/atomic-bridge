# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import logging
import secrets

import pytest
from aiohttp import WSMsgType, WSServerHandshakeError

from atomic_relay.web.runtime import RUNTIME
from atomic_relay.sessions.grants import DEFAULT_TTL_SECONDS, REFRESH_TTL_SECONDS
from fake_phone import FakePhone, session_payload
from session_cases import FIXTURE, application
from e2e_support import relay_log


def test_pair_without_ttl_uses_thirty_days_and_refresh_slides_trust(tmp_path):
    now = [1000]
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            sessions = app[RUNTIME].sessions
            sessions.clock = lambda: now[0]
            synthetic = secrets.token_hex(3).upper()
            response = await client.post("/pairing/register", json={"code": synthetic})
            assert response.status == 200
            phone = await FakePhone.connect(client, {"pairing_code": synthetic, "device_id": "finite-device"})
            auth = phone.auth["payload"]
            assert auth["expires_at"] == now[0] + DEFAULT_TTL_SECONDS
            assert auth["grants"]["bridge"] == auth["expires_at"]
            assert all(expiry == auth["expires_at"] for expiry in auth["grants"].values())
            await phone.close()
            now[0] += DEFAULT_TTL_SECONDS + 1
            refreshed = await FakePhone.connect(client, {"refresh_token": auth["refresh_token"], "device_id": "finite-device"})
            current = refreshed.auth["payload"]
            assert current["expires_at"] == now[0] + DEFAULT_TTL_SECONDS
            assert current["grants"]["bridge"] == current["expires_at"]
            await refreshed.close()
            now[0] = 1000 + REFRESH_TTL_SECONDS + 1
            again = await FakePhone.connect(client, {"refresh_token": current["refresh_token"], "device_id": "finite-device"})
            await again.close()
    asyncio.run(check())


@pytest.mark.parametrize("trusted", [False, True])
def test_attack_rotating_forwarded_addresses_cannot_evade_the_resolved_bucket(tmp_path, trusted):
    path = tmp_path / "address.log"
    async def check():
        with relay_log(path):
            async with application(tmp_path, admin=True) as (app, client):
                runtime = app[RUNTIME]
                # This seam lets the same runtime attack run on the pre-setting base.
                runtime.trusted_proxies = frozenset({"127.0.0.1"}) if trusted else frozenset()
                for number in range(1, 6):
                    headers = {"X-Forwarded-For": f"203.0.113.{number}, 198.51.100.41, 127.0.0.1",
                               "X-Forwarded-Proto": "https"}
                    phone = await FakePhone.connect(client, {"session_token": "synthetic-invalid"}, headers=headers, expected="auth.fail")
                    await phone.close()
                with pytest.raises(WSServerHandshakeError) as refused:
                    await client.ws_connect("/ws", headers={"X-Forwarded-For": "203.0.113.99, 198.51.100.41, 127.0.0.1"})
                assert refused.value.status == 429
    asyncio.run(check())
    address = "198.51.100.41" if trusted else "127.0.0.1"
    text = path.read_text()
    assert any(line.endswith(f" WARNING atomic_relay: WebSocket from blocked IP {address} — rejecting") for line in text.splitlines())
    assert f"IP {address} blocked for 300s after 5 failed session auth attempts" in text
    assert "synthetic-invalid" not in text and "203.0.113." not in text


def test_attack_twenty_pairing_failures_across_addresses_invalidate_pending_code(tmp_path, caplog):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            app[RUNTIME].trusted_proxies = frozenset({"127.0.0.1"})
            code, fresh = secrets.token_hex(3).upper(), secrets.token_hex(3).upper()
            assert (await client.post("/pairing/register", json={"code": code})).status == 200
            for number in range(1, 21):
                failed = await FakePhone.connect(client, {"pairing_code": "ZZZZZZ"},
                                                headers={"X-Forwarded-For": f"198.51.100.{number}"}, expected="auth.fail")
                await failed.close()
            failed = await FakePhone.connect(client, {"pairing_code": code, "device_id": "cap-test"},
                                            headers={"X-Forwarded-For": "198.51.100.100"}, expected="auth.fail")
            await failed.close()
            assert (await client.post("/pairing/register", json={"code": fresh})).status == 200
            phone = await FakePhone.connect(client, {"pairing_code": fresh, "device_id": "cap-test"},
                                           headers={"X-Forwarded-For": "198.51.100.100"})
            await phone.close()
            assert code not in caplog.text and fresh not in caplog.text and "ZZZZZZ" not in caplog.text
            assert "component=pairing operation=abuse-cap cleared pending codes after 20 failures" in caplog.text
    asyncio.run(check())


def test_attack_seventeenth_pending_websocket_is_refused_before_upgrade(tmp_path, caplog):
    async def check():
        async with application(tmp_path) as (_, client):
            sockets = []
            try:
                for _ in range(16):
                    sockets.append(await client.ws_connect("/ws"))
                with pytest.raises(WSServerHandshakeError) as refused:
                    await client.ws_connect("/ws")
                assert refused.value.status == 503
                assert "component=websocket operation=upgrade refused pending=16" in caplog.text
            finally:
                await asyncio.wait_for(asyncio.gather(*(socket.close() for socket in sockets)), timeout=3)
    asyncio.run(check())


async def pending_count(runtime, expected):
    async def reached():
        while runtime.pending_auth != expected:
            await asyncio.sleep(0)
    await asyncio.wait_for(reached(), timeout=2)


@pytest.mark.parametrize("release", ["auth.ok", "auth.fail", "close"])
def test_pending_slot_is_released_on_authentication_failure_or_close(tmp_path, release):
    async def check():
        async with application(tmp_path) as (app, client):
            runtime, sockets = app[RUNTIME], []
            try:
                for _ in range(16):
                    sockets.append(await client.ws_connect("/ws"))
                await pending_count(runtime, 16)
                phone = FakePhone(sockets[0], None)
                if release == "close":
                    await phone.close()
                else:
                    session = runtime.sessions.create_session("Synthetic", "pending-slot", ttl_seconds=0)
                    payload = session_payload(session) if release == "auth.ok" else {"session_token": "synthetic-invalid"}
                    await phone.send("system", "auth", payload)
                    assert (await phone.receive())["type"] == release
                await pending_count(runtime, 15)
                sockets.append(await client.ws_connect("/ws"))
                await pending_count(runtime, 16)
                if release == "auth.ok":
                    assert not sockets[0].closed
                    await phone.sync()
            finally:
                await asyncio.wait_for(asyncio.gather(*(socket.close() for socket in sockets)), timeout=3)
            await pending_count(runtime, 0)
    asyncio.run(check())


def test_prepare_failure_releases_pending_slot_and_server_recovers(tmp_path, monkeypatch, caplog):
    from atomic_relay.web import ws
    private = "synthetic-private-upgrade-failure"
    async def check():
        async with application(tmp_path) as (app, client):
            original = ws.web.WebSocketResponse.prepare
            async def failed(self, request):
                raise OSError(private)
            monkeypatch.setattr(ws.web.WebSocketResponse, "prepare", failed)
            response = await client.get("/ws")
            assert response.status == 500
            await pending_count(app[RUNTIME], 0)
            assert private not in await response.text() + caplog.text
            monkeypatch.setattr(ws.web.WebSocketResponse, "prepare", original)
            session = app[RUNTIME].sessions.create_session("Synthetic", "recovered-slot", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            await pending_count(app[RUNTIME], 0)
            await phone.sync()
            await phone.close()
    asyncio.run(check())


@pytest.mark.parametrize("trusted,expected", [(False, "ws"), (True, "wss")])
def test_pairing_transport_hint_uses_forwarded_tls_only_from_trusted_peer(tmp_path, trusted, expected):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            app[RUNTIME].trusted_proxies = frozenset({"127.0.0.1"}) if trusted else frozenset()
            code = secrets.token_hex(3).upper()
            assert (await client.post("/pairing/register", json={"code": code})).status == 200
            phone = await FakePhone.connect(client, {"pairing_code": code, "device_id": "transport-test"},
                                           headers={"X-Forwarded-For": "198.51.100.6", "X-Forwarded-Proto": "https"})
            assert phone.auth["payload"]["transport_hint"] == expected
            await phone.close()
    asyncio.run(check())


def test_stored_fixture_session_reconnects_without_pairing_and_health_is_dynamic(tmp_path):
    stored = tmp_path / "sessions.json"
    stored.write_bytes(FIXTURE.read_bytes())
    async def check():
        async with application(tmp_path, stored, admin=True) as (app, client):
            session = app[RUNTIME].sessions.list_sessions()[0]
            phone = await FakePhone.connect(client, session_payload(session), path="/")
            auth = phone.auth["payload"]
            matches = auth == {"session_token": session.token, "server_version": "2.3.0", "profiles": [],
                            "expires_at": None, "grants": {name: None for name in session.grants},
                            "transport_hint": "wss", "client_surface": "android", "device_form_factor": "phone"}
            assert matches
            health = await (await client.get("/health")).json()
            assert health["clients"] == 1 and not health["phone_connected"]
            await phone.status({"device": {"name": "Synthetic", "extra": 7}})
            assert (await (await client.get("/health")).json())["phone_connected"]
            await phone.close()
    asyncio.run(check())


def test_isolated_pair_and_session_reconnect_smoke(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            runtime = app[RUNTIME]
            response = await client.get("/health")
            assert response.status == 200 and (await response.json())["status"] == "ok"
            assert (await client.get("/bridge/status", headers={"Forwarded": "synthetic"})).status == 403
            response = await client.post("/pairing/register", json={"code": "ABC123", "ttl_seconds": 0, "grants": {"bridge": 0}})
            assert response.status == 200
            phone = await FakePhone.connect(client, {"pairing_code": "ABC123", "device_id": "smoke-device",
                                                     "client_surface": "android", "device_form_factor": "phone"})
            auth = phone.auth["payload"]
            assert auth["refresh_token"]
            assert auth["expires_at"] is None
            assert auth["grants"]["bridge"] is None
            await phone.status()
            await phone.close()
            reconnected = await FakePhone.connect(client, {"session_token": auth["session_token"], "device_id": "smoke-device"})
            await reconnected.status()
            assert (await (await client.get("/bridge/devices")).json())["count"] == 1
            assert runtime.sessions.active_count() == 1
            await reconnected.close()
    asyncio.run(check())


def test_refresh_reconnect_rotates_and_preserves_old_session(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            sessions = app[RUNTIME].sessions
            old = sessions.create_session("Synthetic", "refresh-device", ttl_seconds=0, issue_refresh_token=True)
            original = old.refresh_token
            phone = await FakePhone.connect(client, {"session_token": "synthetic-stale", "refresh_token": original,
                                                     "device_id": old.device_id})
            auth = phone.auth["payload"]
            rotated = auth["session_token"] != old.token and auth["refresh_token"] != original
            assert rotated
            assert sessions.get_session(old.token) is old
            await phone.close()
            failed = await FakePhone.connect(client, {"refresh_token": original, "device_id": old.device_id}, expected="auth.fail")
            await failed.close()
            current = await FakePhone.connect(client, {"refresh_token": auth["refresh_token"], "device_id": old.device_id})
            await current.close()
    asyncio.run(check())


def test_forwarded_address_failure_buckets_block_upgrade_only_for_that_address(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            app[RUNTIME].trusted_proxies = frozenset({"127.0.0.1"})
            headers = {"X-Forwarded-For": "198.51.100.9, 127.0.0.1"}
            for _ in range(5):
                failed = await FakePhone.connect(client, {"session_token": "synthetic-stale"}, headers=headers, expected="auth.fail")
                assert failed.auth["payload"]["reason"] == "Invalid pairing code or session token"
                await failed.close()
            assert app[RUNTIME].rate_limiter.is_blocked("198.51.100.9")
            with pytest.raises(WSServerHandshakeError) as error:
                await asyncio.wait_for(client.ws_connect("/ws", headers=headers), timeout=2)
            assert error.value.status == 429
            valid = app[RUNTIME].sessions.create_session("Synthetic", "other-device", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(valid))
            await phone.close()
    asyncio.run(check())


@pytest.mark.parametrize(("raw", "reason"), [
    ('{"channel":"bridge","type":"bridge.status"}', "First message must be system/auth"),
    ("{invalid", "Invalid message format"), ("[]", "Invalid message format"),
    ('{"channel":"system","type":"auth","payload":[]}', "Invalid message format"),
])
def test_invalid_first_frame_refused_and_closed(tmp_path, raw, reason):
    async def check():
        async with application(tmp_path, admin=True) as (_, client):
            socket = await asyncio.wait_for(client.ws_connect("/ws"), timeout=2)
            await asyncio.wait_for(socket.send_str(raw), timeout=2)
            response = await asyncio.wait_for(socket.receive_json(), timeout=2)
            assert response["type"] == "auth.fail" and response["payload"]["reason"] == reason
            assert (await asyncio.wait_for(socket.receive(), timeout=2)).type in {WSMsgType.CLOSE, WSMsgType.CLOSED}
            await asyncio.wait_for(socket.close(), timeout=2)
    asyncio.run(check())


def test_authentication_deadline_closes_without_a_frame(tmp_path):
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            assert app[RUNTIME].auth_timeout == 30
            app[RUNTIME].auth_timeout = 0.001
            socket = await asyncio.wait_for(client.ws_connect("/ws"), timeout=2)
            response = await asyncio.wait_for(socket.receive_json(), timeout=2)
            assert response["type"] == "auth.fail" and response["payload"]["reason"] == "Authentication timeout"
            assert (await asyncio.wait_for(socket.receive(), timeout=2)).type == WSMsgType.CLOSE
            await pending_count(app[RUNTIME], 0)
            await asyncio.wait_for(socket.close(), timeout=2)
    asyncio.run(check())


def test_authentication_dependency_failure_is_private_and_next_connection_survives(tmp_path, monkeypatch, caplog):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    private = "synthetic-private-auth-dependency"
    async def check():
        async with application(tmp_path, admin=True) as (app, client):
            runtime = app[RUNTIME]
            session = runtime.sessions.create_session("Synthetic", "auth-failure-device", ttl_seconds=0)
            original = runtime.authentication.authenticate
            def failed(*args, **kwargs):
                raise OSError(private)
            monkeypatch.setattr(runtime.authentication, "authenticate", failed)
            refused = await FakePhone.connect(client, session_payload(session), expected="auth.fail")
            assert refused.auth["payload"] == {"reason": "Connection failed"}
            await refused.close()
            monkeypatch.setattr(runtime.authentication, "authenticate", original)
            phone = await FakePhone.connect(client, session_payload(session))
            await phone.sync()
            await phone.close()
    with caplog.at_level(logging.ERROR):
        asyncio.run(check())
    assert private not in caplog.text
    assert any(row.exc_info and "operation=connection" in row.message for row in caplog.records)
