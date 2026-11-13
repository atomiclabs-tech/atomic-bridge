"""Isolated real-relay scenarios shared by integration and operator-helper tests."""

import asyncio
import hashlib
import logging
import os
import secrets
import stat
from contextlib import asynccontextmanager, contextmanager
from dataclasses import replace
from pathlib import Path

from aiohttp import ClientTimeout, FormData
from aiohttp.test_utils import TestClient, TestServer
import pytest

from atomic_bridge import register
from atomic_relay.logsetup import configure_logging
from atomic_relay.web.app import create_app
from atomic_relay.web.runtime import RUNTIME
from fake_phone import FakePhone, session_payload
from recording_ctx import RecordingContext
from session_cases import FIXTURE, configuration


class Loopback:
    def __init__(self, app, client, stored):
        self.app, self.client, self.stored = app, client, stored
        self.runtime = app[RUNTIME]
        self.session = self.runtime.sessions.list_sessions()[0]
        self.secret_file = stored.with_name("admin.secret")
        self.credentials = {self.session.token, self.secret_file.read_text().strip()}
        ctx = RecordingContext()
        register(ctx)
        self.handlers = {tool["name"]: tool["handler"] for tool in ctx.tools}
        assert len(ctx.tools) == len(self.handlers) == 42

    async def connect(self, **kwargs):
        return await FakePhone.connect(self.client, session_payload(self.session), **kwargs)

    async def invoke(self, name, args=None):
        assert "ANDROID_BRIDGE_SESSIONS_FILE" not in os.environ
        assert "ANDROID_BRIDGE_TOKEN" not in os.environ
        assert stat.S_IMODE(self.stored.stat().st_mode) == 0
        result = await asyncio.wait_for(asyncio.to_thread(self.handlers[name], args or {}), timeout=5)
        assert stat.S_IMODE(self.stored.stat().st_mode) == 0
        return result


@asynccontextmanager
async def loopback(tmp_path, monkeypatch):
    stored = tmp_path / "sessions.json"
    stored.write_bytes(FIXTURE.read_bytes())
    stored.chmod(0o600)
    secret = tmp_path / "admin.secret"
    secret.touch(mode=0o600)
    secret.chmod(0o600)
    secret.write_text(secrets.token_urlsafe(32) + "\n")
    config = replace(configuration(tmp_path, stored), media_allowed_roots=(tmp_path,))
    app = create_app(config)
    unchanged = stored.read_bytes() == FIXTURE.read_bytes()
    assert unchanged
    server = TestServer(app, port=0)
    await asyncio.wait_for(server.start_server(access_log=None), timeout=5)
    client = TestClient(server, timeout=ClientTimeout(total=5))
    await asyncio.wait_for(client.start_server(), timeout=5)
    monkeypatch.setenv("ANDROID_BRIDGE_URL", str(client.make_url("/")).rstrip("/"))
    monkeypatch.setenv("ATOMIC_BRIDGE_ADMIN_SECRET_FILE", str(tmp_path / "admin.secret"))
    monkeypatch.setenv("ANDROID_BRIDGE_TIMEOUT", "2")
    monkeypatch.delenv("ANDROID_BRIDGE_SESSIONS_FILE", raising=False)
    monkeypatch.delenv("ANDROID_BRIDGE_TOKEN", raising=False)
    original_mode = stat.S_IMODE(stored.stat().st_mode)
    stored.chmod(0)
    try:
        with pytest.raises(PermissionError):
            stored.read_bytes()
        yield Loopback(app, client, stored)
    finally:
        stored.chmod(original_mode)
        await asyncio.wait_for(client.close(), timeout=5)


async def until(predicate):
    async def poll():
        while not predicate():
            await asyncio.sleep(0.001)
    await asyncio.wait_for(poll(), timeout=2)


async def upload(relay, chunks, *, content_type="application/octet-stream"):
    async def stream():
        for chunk in chunks:
            yield chunk
    data = FormData()
    data.add_field("file", stream(), filename="synthetic.bin", content_type=content_type)
    async with relay.client.post("/media/upload", data=data, headers={
        "Authorization": "Bearer " + relay.session.token,
    }) as response:
        assert response.status == 200
        metadata = await asyncio.wait_for(response.json(), timeout=2)
    assert metadata["ok"] is True
    relay.credentials.add(metadata["token"])
    return metadata


async def fetch(relay, marker):
    token = marker.removeprefix("MEDIA:hermes-relay://")
    relay.credentials.add(token)
    digest, size = hashlib.sha256(), 0
    async with relay.client.get("/media/" + token, headers={
        "Authorization": "Bearer " + relay.session.token,
    }) as response:
        assert response.status == 200
        async with asyncio.timeout(5):
            async for chunk in response.content.iter_chunked(64 * 1024):
                digest.update(chunk)
                size += len(chunk)
    return size, digest.hexdigest()


def file_digest(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        while chunk := source.read(64 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


@contextmanager
def relay_log(path):
    logger = logging.getLogger("atomic_relay")
    handlers, level, propagate = logger.handlers[:], logger.level, logger.propagate
    access = logging.getLogger("aiohttp.access")
    disabled = access.disabled
    for handler in handlers:
        logger.removeHandler(handler)
    configure_logging(path, "INFO")
    try:
        yield
    finally:
        for handler in logger.handlers[:]:
            logger.removeHandler(handler)
            handler.close()
        for handler in handlers:
            logger.addHandler(handler)
        logger.setLevel(level)
        logger.propagate = propagate
        access.disabled = disabled
