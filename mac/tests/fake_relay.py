"""Threaded aiohttp relay double; every socket binds an ephemeral loopback port."""

import asyncio
import concurrent.futures
import logging
import os
import secrets
import threading
from dataclasses import dataclass
from pathlib import Path

from aiohttp import web


@dataclass
class FakeResponse:
    status: int = 200
    value: dict | None = None
    text: str | None = None
    headers: dict | None = None


class AdminEnvironment(dict):
    def get(self, key, default=None):
        assert key in {"ANDROID_BRIDGE_URL", "ANDROID_BRIDGE_TIMEOUT", "ATOMIC_BRIDGE_ADMIN_SECRET_FILE"}
        return super().get(key, default)


def admin_environment(**values):
    return AdminEnvironment({"ATOMIC_BRIDGE_ADMIN_SECRET_FILE": os.environ["ATOMIC_BRIDGE_ADMIN_SECRET_FILE"], **values})


class FakeRelay:
    def __init__(self, respond=None, *, secret=None):
        self.expected_secret = (Path(admin_environment()["ATOMIC_BRIDGE_ADMIN_SECRET_FILE"]).read_text().strip()
                                if secret is None else secret)
        self.requests = []
        self.rejected = 0
        self.respond = respond or (lambda request: FakeResponse(value={"success": True}))
        self._ready = concurrent.futures.Future()
        self._done = concurrent.futures.Future()

    async def _handle(self, request):
        if not secrets.compare_digest(request.headers.get("Authorization", ""), "Bearer " + self.expected_secret):
            self.rejected += 1
            return web.json_response({"error": "admin credential required"}, status=401)
        body = await asyncio.wait_for(request.json(), timeout=2) if request.can_read_body else None
        recorded = {
            "method": request.method, "path": request.path,
            "query": dict(request.query), "headers": dict(request.headers), "body": body,
        }
        self.requests.append(recorded)
        response = self.respond(recorded)
        if response.text is not None:
            return web.Response(status=response.status, text=response.text, headers=response.headers)
        return web.json_response(response.value, status=response.status, headers=response.headers)

    async def _serve(self):
        self._loop = asyncio.get_running_loop()
        self._stop = asyncio.Event()
        app = web.Application()
        app.router.add_route("*", "/{path:.*}", self._handle)
        runner = web.AppRunner(app, access_log=None)
        await asyncio.wait_for(runner.setup(), timeout=2)
        try:
            site = web.TCPSite(runner, "127.0.0.1", 0)
            await asyncio.wait_for(site.start(), timeout=2)
            host, port = runner.addresses[0]
            self.url = f"http://{host}:{port}"
            self._ready.set_result(None)
            await self._stop.wait()
        finally:
            await asyncio.wait_for(runner.cleanup(), timeout=2)

    def _run(self):
        try:
            asyncio.run(self._serve())
        except Exception as exc:
            logging.getLogger(__name__).exception("Fake relay failed")
            if not self._ready.done():
                self._ready.set_exception(exc)
            self._done.set_exception(exc)
        else:
            self._done.set_result(None)

    def __enter__(self):
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        self._ready.result(timeout=5)
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self._loop.call_soon_threadsafe(self._stop.set)
        self._thread.join(timeout=5)
        if self._thread.is_alive():
            raise TimeoutError("Fake relay thread did not stop")
        self._done.result(timeout=1)
