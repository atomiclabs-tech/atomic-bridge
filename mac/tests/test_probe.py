import asyncio
import logging
import urllib.error
# no_parallel: bounded HTTP test servers bind port 0

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from atomic_relay.ops.probe import Probe, healthy, phone_connected, wait_for
from ops_cases import FakeClock, FakeProbe


@pytest.fixture(autouse=True)
def capture_operator_logger(caplog):
    logger = logging.getLogger("atomic_relay.ops")
    previous = logger.propagate
    logger.propagate = False
    logger.addHandler(caplog.handler)
    yield
    logger.removeHandler(caplog.handler)
    logger.propagate = previous


@pytest.mark.parametrize("status,payload", [(200, {"status": "ok"}), (503, {"error": "synthetic"}), (200, [])])
def test_real_bounded_loopback_http_checks_status_shape_and_ignores_proxies(status, payload, monkeypatch):
    async def check():
        async def reply(request):
            return web.json_response(payload, status=status)
        app = web.Application()
        app.router.add_get("/health", reply)
        monkeypatch.setattr("urllib.request.getproxies", lambda: {"http": "http://unreachable.example:1"})
        async with TestClient(TestServer(app, port=0)) as client:
            probe = Probe(str(client.make_url("/")).rstrip("/"))
            if status == 200 and isinstance(payload, dict):
                assert await asyncio.to_thread(probe.get, "/health", timeout=1) == payload
            else:
                with pytest.raises((ValueError, urllib.error.HTTPError)):
                    await asyncio.to_thread(probe.get, "/health", timeout=1)
    asyncio.run(check())


def test_http_redirect_is_not_followed():
    async def check():
        followed = []
        async def target(request):
            followed.append(True)
            return web.json_response({"status": "ok"})
        async def redirect(request):
            return web.Response(status=302, headers={"Location": "/target"})
        app = web.Application()
        app.router.add_get("/health", redirect)
        app.router.add_get("/target", target)
        async with TestClient(TestServer(app, port=0)) as client:
            with pytest.raises(urllib.error.HTTPError):
                await asyncio.to_thread(Probe(str(client.make_url("/")).rstrip("/")).get, "/health", timeout=1)
            assert not followed
    asyncio.run(check())


def test_poll_retries_are_bounded_by_fake_clock_and_logged(caplog):
    clock, probe = FakeClock(), FakeProbe(["old", "old", "new"])
    with caplog.at_level(logging.WARNING, logger="atomic_relay.ops"):
        wait_for(probe, "/health", lambda data: healthy(data, "new"), budget=3, clock=clock.clock, sleep=clock.sleep)
    assert clock.sleeps == [1, 1]
    assert len([record for record in caplog.records if "retry" in record.message]) == 2
    assert any(record.exc_info for record in caplog.records)
    clock, probe = FakeClock(), FakeProbe(["old"])
    with pytest.raises(ValueError, match="expected state"):
        wait_for(probe, "/health", lambda data: healthy(data, "new"), budget=2, clock=clock.clock, sleep=clock.sleep)
    assert sum(clock.sleeps) == 2
    assert len(probe.calls) == 3
    assert all(0 < timeout <= 2 for _, timeout in probe.calls)


def test_verify_never_sleeps_and_device_check_requires_atomic_phone():
    clock, probe = FakeClock(), FakeProbe(["old"])
    with pytest.raises(ValueError):
        wait_for(probe, "/health", lambda data: healthy(data, "new"), budget=0, clock=clock.clock, sleep=clock.sleep)
    assert len(probe.calls) == 1 and not clock.sleeps
    payload = FakeProbe(["new"]).get("/bridge/devices")
    assert phone_connected(payload)
    payload["devices"][0]["device"]["application_id"] = "other.example"
    assert not phone_connected(payload)
    with pytest.raises(ValueError, match="shape"):
        phone_connected({"devices": [None]})


def test_bearer_on_both_routes_and_401_is_bounded_and_redacted(tmp_path, caplog):
    secret = "synthetic-operator-secret-not-for-logs-" * 2
    path = tmp_path / "admin.secret"
    path.write_text(secret)
    path.chmod(0o600)
    async def check():
        seen = []
        async def reply(request):
            seen.append((request.path, request.headers.get("Authorization")))
            return web.json_response({"echo": secret}, status=401)
        app = web.Application()
        app.router.add_get("/health", reply)
        app.router.add_get("/bridge/devices", reply)
        async with TestClient(TestServer(app, port=0)) as client:
            probe = Probe(str(client.make_url("/")).rstrip("/"), path)
            for route in ("/health", "/bridge/devices"):
                clock = FakeClock()
                with pytest.raises(urllib.error.HTTPError) as result:
                    await asyncio.to_thread(wait_for, probe, route, lambda data: True, budget=0,
                                            clock=clock.clock, sleep=clock.sleep)
                assert result.value.code == 401 and clock.sleeps == []
            assert seen == [(route, "Bearer " + secret) for route in ("/health", "/bridge/devices")]
    with caplog.at_level(logging.ERROR, logger="atomic_relay.ops"):
        asyncio.run(check())
    assert secret not in caplog.text and "HTTP check failed" in caplog.text and "status=401" in caplog.text
    assert all(record.exc_info for record in caplog.records)


def test_attack_bearer_is_required_by_relay_on_both_operator_routes(tmp_path):
    """A credentialed operator probe must satisfy the relay, not merely reach it."""
    secret = "synthetic-bearer-wire-proof-" * 2
    path = tmp_path / "admin.secret"
    path.write_text(secret)
    path.chmod(0o600)
    async def check():
        async def reply(request):
            if request.headers.get("Authorization") != "Bearer " + secret:
                return web.json_response({"error": "admin credential required"}, status=401)
            return web.json_response({"status": "ok"})
        app = web.Application()
        app.router.add_get("/health", reply)
        app.router.add_get("/bridge/devices", reply)
        async with TestClient(TestServer(app, port=0)) as client:
            probe = Probe(str(client.make_url("/")).rstrip("/"))
            probe.secret_file = path
            for route in ("/health", "/bridge/devices"):
                assert await asyncio.to_thread(probe.get, route, timeout=1) == {"status": "ok"}
    asyncio.run(check())
