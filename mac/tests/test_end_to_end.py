# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import argparse
import base64
import hashlib
import json
import math
import re
import secrets
import stat
import time

import pytest
from aiohttp import WSServerHandshakeError

from e2e_support import fetch, file_digest, loopback, relay_log, until, upload
from fake_phone import FakePhone
from atomic_relay import cli_remote
from tool_cases import PNG
from session_cases import FIXTURE, admin_headers, cli_env


def test_public_health_reports_220_and_exact_public_fields(tmp_path, monkeypatch):
    async def check():
        async with loopback(tmp_path, monkeypatch) as relay:
            response = await relay.client.get("/health")
            value = await response.json()
            assert response.status == 200
            assert set(value) == {"status", "version", "uptime_seconds"}
            assert value["status"] == "ok" and value["version"] == "2.3.0"
    asyncio.run(check())


@pytest.mark.parametrize("hold", [None, 600], ids=["default", "explicit"])
def test_registered_drag_hold_and_search_fields_cross_real_relay(tmp_path, monkeypatch, hold):
    async def check():
        async with loopback(tmp_path, monkeypatch) as relay:
            phone = await relay.connect()
            await phone.status()
            arguments = {"start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4}
            if hold is not None:
                arguments["hold_ms"] = hold
            pending = asyncio.create_task(relay.invoke("android_drag", arguments))
            command = await phone.receive()
            expected_hold = 0 if hold is None else hold
            result = {"success": True, "hold_ms": expected_hold}
            await phone.respond(command, result=result)
            assert json.loads(await pending) == result
            assert command["payload"]["path"] == "/drag"
            assert command["payload"]["body"] == {
                "start_x": 1, "start_y": 2, "end_x": 3, "end_y": 4,
                "duration_ms": 500, "hold_ms": expected_hold,
            }
            pending = asyncio.create_task(relay.invoke("android_find_nodes", {"text": "synthetic", "limit": 1}))
            command = await phone.receive()
            assert command["payload"]["path"] == "/find_nodes"
            result = {"success": True, "nodes": [], "truncated": True, "visited": 20000}
            await phone.respond(command, result=result)
            assert json.loads(await pending) == result
            await phone.close()
    asyncio.run(check())


def test_fixture_authentication_reconnect_and_registered_ping(tmp_path, monkeypatch):
    original = FIXTURE.read_bytes()
    async def check():
        async with loopback(tmp_path, monkeypatch) as relay:
            assert relay.session.expires_at == math.inf
            assert all(expiry == math.inf for expiry in relay.session.grants.values())
            for path in ("/", "/ws"):
                phone = await relay.connect(path=path)
                same = phone.auth["payload"]["session_token"] == relay.session.token
                assert same, "Stored fixture session was not retained"
                assert relay.runtime.sessions.active_count() == 1
                health = await (await relay.client.get("/health", headers=admin_headers(relay.app))).json()
                assert health["clients"] == 1 and not health["phone_connected"]
                await phone.status()
                pending = asyncio.create_task(relay.invoke("android_ping"))
                command = await phone.receive()
                assert (command["channel"], command["type"], command["payload"]["path"]) == (
                    "bridge", "bridge.command", "/ping",
                )
                await phone.respond(command, result={"success": True, "pong": True})
                assert json.loads(await pending) == {"success": True, "pong": True}
                await phone.close()
                await until(lambda: not relay.runtime.clients)
        actual = json.loads(relay.stored.read_bytes())["sessions"][0]
        expected = json.loads(original)["sessions"][0]
        preserved = all(actual[key] == expected[key] for key in ("token", "expires_at", "grants"))
        assert preserved
    asyncio.run(check())
    assert FIXTURE.read_bytes() == original


def test_credentialed_cli_pair_connects_and_rotates_refresh_without_changing_fixture(tmp_path, monkeypatch):
    async def check():
        async with loopback(tmp_path, monkeypatch) as relay:
            code = secrets.token_hex(3).upper()
            relay.credentials.add(code)
            parser = argparse.ArgumentParser()
            cli_remote.add_commands(parser.add_subparsers(dest="command", required=True))
            args = parser.parse_args(["pair", "--register-code", code, "--port", str(relay.client.port),
                                      "--env-file", str(cli_env(tmp_path))])
            exit_code, result = await asyncio.wait_for(asyncio.to_thread(cli_remote.pair, args), timeout=5)
            assert exit_code == 0 and result == {"ok": True}
            phone = await FakePhone.connect(relay.client, {"pairing_code": code, "device_id": "fresh-device"})
            auth = phone.auth["payload"]
            relay.credentials.update((auth["session_token"], auth["refresh_token"]))
            assert auth["expires_at"] is not None and auth["grants"]["bridge"] == auth["expires_at"]
            await phone.status()
            await phone.close()
            refreshed = await FakePhone.connect(relay.client, {"refresh_token": auth["refresh_token"], "device_id": "fresh-device"})
            current = refreshed.auth["payload"]
            rotated = current["session_token"] != auth["session_token"] and current["refresh_token"] != auth["refresh_token"]
            assert rotated
            relay.credentials.update((current["session_token"], current["refresh_token"]))
            await refreshed.status()
            await refreshed.close()
            replay = await FakePhone.connect(relay.client, {"refresh_token": auth["refresh_token"], "device_id": "fresh-device"}, expected="auth.fail")
            await replay.close()
            assert relay.runtime.sessions.get_session(relay.session.token) is relay.session
            assert relay.session.expires_at == math.inf and all(value == math.inf for value in relay.session.grants.values())
    asyncio.run(check())


def test_registered_setup_and_missing_or_wrong_admin_credentials(tmp_path, monkeypatch):
    async def check():
        async with loopback(tmp_path, monkeypatch) as relay:
            phone = await relay.connect()
            await phone.status()
            code = secrets.token_hex(3).upper()
            relay.credentials.add(code)
            pending = asyncio.create_task(relay.invoke("android_setup", {"pairing_code": code}))
            command = await phone.receive()
            assert command["payload"]["path"] == "/ping"
            await phone.respond(command, result={"success": True, "pong": True})
            raw = await pending
            result = json.loads(raw)
            assert set(result["diagnostics"]) == {"/pairing/register", "/health", "/bridge/status", "/ping"}
            assert result["success"] is True and all(row["status"] == 200 for row in result["diagnostics"].values())
            hidden = code not in raw
            assert hidden
            wrong = tmp_path / "wrong.secret"
            wrong.touch(mode=0o600)
            value = secrets.token_urlsafe(32)
            wrong.write_text(value)
            relay.credentials.add(value)
            for path in (tmp_path / "absent.secret", wrong):
                with monkeypatch.context() as environment:
                    environment.setenv("ATOMIC_BRIDGE_ADMIN_SECRET_FILE", str(path))
                    refused = json.loads(await relay.invoke("android_ping"))
                    failed = "error" in refused
                    assert failed
                    if path == wrong:
                        assert refused["status"] == 401
            response = await relay.client.get("/bridge/status")
            status = response.status
            assert status == 401
            await phone.close()
        assert stat.S_IMODE(relay.stored.stat().st_mode) == 0o600
    asyncio.run(check())


def test_uploaded_screenshot_envelope_and_real_phone_failure(tmp_path, monkeypatch):
    async def check():
        async with loopback(tmp_path, monkeypatch) as relay:
            phone = await relay.connect()
            await phone.status()
            pending = asyncio.create_task(relay.invoke("android_screenshot"))
            command = await phone.receive()
            assert command["payload"]["path"] == "/screenshot"
            image = await upload(relay, [PNG], content_type="image/png")
            await phone.respond(command, result={"success": True, "media": "MEDIA:hermes-relay://" + image["token"]})
            result = await pending
            assert isinstance(result, dict) and result["_multimodal"] is True
            image_url = result["content"][1]["image_url"]["url"]
            png_data_url = image_url.startswith("data:image/png;base64,")
            assert png_data_url
            same_image = base64.b64decode(image_url.split(",", 1)[1]) == PNG
            assert same_image
            dimensions_reported = "1x1" in result["text_summary"]
            assert dimensions_reported
            pending = asyncio.create_task(relay.invoke("android_screenshot"))
            command = await phone.receive()
            failure = {"success": False, "code": "internal_error", "error": "Synthetic screenshot capture failed"}
            await phone.respond(command, result=failure)
            raw = await pending
            assert isinstance(raw, str) and json.loads(raw) == {**failure, "stage": "command"}
            await phone.close()
    asyncio.run(check())


def test_notification_frames_reach_recent_and_an_active_wait(tmp_path, monkeypatch):
    async def check():
        async with loopback(tmp_path, monkeypatch) as relay:
            phone = await relay.connect()
            await phone.status()
            posted = {"key": "synthetic-first", "package_name": "synthetic.app", "title": None,
                      "text": "synthetic public notice", "sub_text": None, "posted_at": 1, "ongoing": False}
            removed = {"key": posted["key"], "package_name": posted["package_name"], "removed_at": 2, "reason": 1}
            await phone.send("notifications", "notification.posted", posted)
            await phone.send("notifications", "notification.removed", removed)
            await phone.sync()
            recent = json.loads(await relay.invoke("android_notifications_recent", {"package": "synthetic.app"}))
            assert recent["status"] == "ok" and recent["count"] == 2
            assert [row["type"] for row in recent["notifications"]] == ["notification.removed", "notification.posted"]
            assert recent["notifications"][1]["text"] == posted["text"]
            started = time.monotonic()
            pending = asyncio.create_task(relay.invoke("android_wait_notification", {
                "package": "synthetic.app", "text": "arrival", "timeout_s": 2,
            }))
            await until(lambda: bool(relay.runtime.notification_waiters.pending))
            assert not pending.done()
            arriving = {**posted, "key": "synthetic-arrival", "text": "synthetic arrival", "posted_at": time.time() * 1000 + 1}
            await phone.send("notifications", "notification.posted", arriving)
            matched = json.loads(await pending)
            assert matched["key"] == arriving["key"] and matched["text"] == arriving["text"]
            assert matched["type"] == "notification.posted" and time.monotonic() - started < 2
            await phone.close()
    asyncio.run(check())


def test_five_mib_pull_and_registered_push_verify_both_checksums(tmp_path, monkeypatch):
    async def check():
        async with loopback(tmp_path, monkeypatch) as relay:
            phone = await relay.connect()
            await phone.status()
            destination = tmp_path / "pulled.bin"
            pending = asyncio.create_task(relay.invoke("android_pull_file", {"path": "Download/synthetic.bin", "dest": str(destination)}))
            command = await phone.receive()
            assert command["payload"]["path"] == "/files/pull"
            assert command["payload"]["body"] == {"path": "Download/synthetic.bin"}
            chunk = b"synthetic-public" * 4096
            chunks = [chunk[:65536]] * 80
            expected = hashlib.sha256(b"".join(chunks)).hexdigest()
            media = await upload(relay, chunks)
            assert media["size"] == 5 * 1024 * 1024 and media["sha256"] == expected
            await phone.respond(command, result={"success": True, "name": "synthetic.bin", "size": media["size"],
                                                "sha256": media["sha256"], "media": "MEDIA:hermes-relay://" + media["token"]})
            pulled = json.loads(await pending)
            assert destination.stat().st_size == pulled["size"] == media["size"]
            assert file_digest(destination) == pulled["sha256"] == expected
            assert not list(tmp_path.glob(".atomic-pull-*"))
            source = tmp_path / "mini.txt"
            source.write_bytes(b"synthetic public mini file")
            expected = file_digest(source)
            pending = asyncio.create_task(relay.invoke("android_push_file", {"path": str(source), "name": "phone.txt", "dir": "Download"}))
            command = await phone.receive()
            assert command["payload"]["path"] == "/files/push"
            body = command["payload"]["body"]
            assert body["name"] == "phone.txt" and body["dir"] == "Download" and body["overwrite"] is False
            size, digest = await fetch(relay, body["media"])
            assert size == body["size"] == source.stat().st_size and digest == body["sha256"] == expected
            await phone.respond(command, result={"success": True, "size": size, "sha256": digest, "scanned": True})
            assert json.loads(await pending) == {"success": True, "size": size, "sha256": expected, "scanned": True}
            await phone.close()
    asyncio.run(check())


def test_loopback_guard_and_all_watcher_lines_are_written_without_secrets(tmp_path, monkeypatch):
    log = tmp_path / "watcher.log"
    async def check():
        with relay_log(log):
            async with loopback(tmp_path, monkeypatch) as relay:
                phone = await relay.connect()
                await phone.status()
                response = await relay.client.get("/health")
                health = await response.json()
                assert set(health) == {"status", "version", "uptime_seconds"} and health["status"] == "ok"
                wrong_health = secrets.token_urlsafe(32)
                relay.credentials.add(wrong_health)
                response = await relay.client.get("/health", headers={"Authorization": "Bearer " + wrong_health})
                assert set(await response.json()) == {"status", "version", "uptime_seconds"}
                response = await relay.client.post("/tap", json={}, headers={"Authorization": "Bearer " + relay.session.token})
                status = response.status
                assert status == 401
                assert await response.json() == {"error": "admin credential required"}
                code = secrets.token_hex(3).upper()
                relay.credentials.add(code)
                response = await relay.client.post("/pairing/register", data=json.dumps({"code": code}), headers={
                    **admin_headers(relay.app), "Origin": "https://example.invalid", "Content-Type": "text/plain",
                })
                status = response.status
                assert status == 403
                assert await response.json() == {"ok": False, "error": "browser origin refused"}
                for header in ("X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "Forwarded", "X-Real-IP"):
                    async with relay.client.get("/bridge/status", headers={**admin_headers(relay.app), header: "synthetic"}) as response:
                        status = response.status
                        assert status == 403
                invalid = secrets.token_urlsafe(32)
                relay.credentials.add(invalid)
                for index in range(5):
                    headers = {"X-Forwarded-For": f"198.51.100.{index + 1}"}
                    failed = await FakePhone.connect(relay.client, {"session_token": invalid}, headers=headers, expected="auth.fail")
                    await failed.close()
                assert relay.runtime.rate_limiter.is_blocked("127.0.0.1")
                assert all(not relay.runtime.rate_limiter.is_blocked(f"198.51.100.{index + 1}") for index in range(5))
                with pytest.raises(WSServerHandshakeError) as refused:
                    await asyncio.wait_for(relay.client.ws_connect("/ws", headers={"X-Forwarded-For": "198.51.100.99"}), timeout=2)
                assert refused.value.status == 429
                await phone.close()
                await until(lambda: not relay.runtime.clients)
                leaked = any(value in log.read_text() for value in relay.credentials)
                assert leaked is False
                return relay.session.device_id
    device_id = asyncio.run(check())
    lines = log.read_text().splitlines()
    prefix = r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d,\d{3} "
    text = "\n".join(lines)
    assert len(re.findall(prefix + r"INFO atomic_relay: Auth failed$", text, re.MULTILINE)) == 5
    assert re.search(prefix + r"WARNING atomic_relay: WebSocket from blocked IP 127\.0\.0\.1 — rejecting$", text, re.MULTILINE)
    detached = re.escape(f"INFO atomic_relay.bridge: bridge: detached device {device_id} (client 127.0.0.1 disconnected)")
    assert re.search(prefix + detached + "$", text, re.MULTILINE)
    assert not any("aiohttp.access" in line for line in lines)
