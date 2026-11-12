import asyncio
import logging

from aiohttp import ClientTimeout, web
from aiohttp.test_utils import TestClient, TestServer

from atomic_relay.web.errors import request_errors


def test_unexpected_request_failure_is_logged_and_next_request_survives(caplog, monkeypatch):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    async def check():
        app = web.Application(middlewares=[request_errors])
        async def failed(request):
            raise OSError("synthetic dependency failure")
        async def okay(request):
            return web.json_response({"ok": True})
        app.router.add_get("/failure", failed)
        app.router.add_get("/okay", okay)
        client = TestClient(TestServer(app, port=0), timeout=ClientTimeout(total=2))
        await asyncio.wait_for(client.start_server(), timeout=2)
        try:
            with caplog.at_level(logging.ERROR):
                response = await client.get("/failure")
            assert response.status == 500 and (await response.json())["error"] == "request failed"
            assert caplog.records[-1].exc_info
            assert "route=/failure" in caplog.text
            assert (await client.get("/okay")).status == 200
            assert (await client.get("/missing")).status == 404
        finally:
            await asyncio.wait_for(client.close(), timeout=2)
    asyncio.run(check())
