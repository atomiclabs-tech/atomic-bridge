import asyncio
from contextlib import asynccontextmanager
from dataclasses import replace

from aiohttp import ClientTimeout, FormData
from aiohttp.test_utils import TestClient, TestServer

from atomic_relay.media.paths import MediaPaths
from atomic_relay.web.app import create_app
from session_cases import configuration


def path_policy(tmp_path, monkeypatch, *, cap=1024):
    for name in ("allowed", "media", "system", "home", "outside"):
        (tmp_path / name).mkdir(exist_ok=True)
    monkeypatch.setattr("tempfile.gettempdir", lambda: str(tmp_path / "system"))
    return MediaPaths((tmp_path / "allowed",), tmp_path / "media", cap, home=tmp_path / "home")


@asynccontextmanager
async def media_application(tmp_path, *, cap=256 * 1024 * 1024):
    (tmp_path / "allowed").mkdir(exist_ok=True)
    config = replace(configuration(tmp_path), media_max_bytes=cap, media_allowed_roots=(tmp_path / "allowed",))
    app = create_app(config)
    server = TestServer(app, port=0)
    await asyncio.wait_for(server.start_server(access_log=None), timeout=2)
    client = TestClient(server, timeout=ClientTimeout(total=3))
    await asyncio.wait_for(client.start_server(), timeout=2)
    try:
        yield app, client
    finally:
        await asyncio.wait_for(client.close(), timeout=2)


def multipart(data, *, name="file", content_type="application/octet-stream"):
    result = FormData()
    result.add_field(name, data, filename="synthetic-private-phone-file.bin", content_type=content_type)
    return result
