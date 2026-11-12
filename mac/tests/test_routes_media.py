# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import hashlib
import logging
import stat
import tracemalloc

import pytest
from aiohttp import ClientPayloadError, FormData

from atomic_relay.media.files import CHUNK_SIZE, UploadFile
from atomic_relay.web.runtime import RUNTIME
from media_cases import media_application, multipart
from session_cases import admin_headers


def test_attack_system_temp_file_is_refused_at_registration(tmp_path, monkeypatch):
    temporary = tmp_path / "system"
    temporary.mkdir()
    monkeypatch.setattr("tempfile.gettempdir", lambda: str(temporary))
    source = temporary / "unconfigured"
    source.write_bytes(b"synthetic")
    async def check():
        async with media_application(tmp_path) as (app, client):
            response = await client.post("/media/register", headers=admin_headers(app),
                                         json={"path": str(source), "content_type": "application/octet-stream"})
            status = response.status
            assert status == 403
            assert await response.json() == {"ok": False, "error": "media path is outside allowed roots"}
    asyncio.run(check())


def test_attack_parent_symlink_swap_refuses_download_even_with_same_inode(tmp_path, caplog):
    async def check():
        async with media_application(tmp_path) as (app, client):
            parent = tmp_path / "allowed" / "parent"
            parent.mkdir()
            source = parent / "source"
            source.write_bytes(b"synthetic")
            headers = admin_headers(app)
            response = await client.post("/media/register", headers=headers,
                                         json={"path": str(source), "content_type": "application/octet-stream"})
            assert response.status == 200
            entry = await response.json()
            original = source.stat()
            moved = parent.with_name("moved")
            parent.rename(moved)
            parent.symlink_to(moved, target_is_directory=True)
            assert (source.stat().st_dev, source.stat().st_ino) == (original.st_dev, original.st_ino)
            response = await client.get("/media/" + entry["token"], headers=headers)
            status = response.status
            assert status == 409
            assert await response.json() == {"ok": False, "error": "registered media changed"}
            assert entry["token"] not in caplog.text and str(source) not in caplog.text
    asyncio.run(check())


def bearer(app):
    session = app[RUNTIME].sessions.create_session("Synthetic", "media-device", ttl_seconds=0)
    return {"Authorization": f"Bearer {session.token}"}


@pytest.mark.parametrize("identity", ["admin", "phone"])
def test_upload_and_download_accept_both_credentials_without_loopback_requirement(tmp_path, identity, caplog):
    async def check():
        async with media_application(tmp_path) as (app, client):
            auth = admin_headers(app) if identity == "admin" else bearer(app)
            headers = {**auth, "X-Forwarded-For": "198.51.100.99"}
            response = await client.post("/media/upload", data=multipart(b"synthetic media"), headers=headers)
            assert response.status == 200
            entry = await response.json()
            response = await client.get("/media/" + entry["token"], headers=headers)
            assert response.status == 200 and await response.read() == b"synthetic media"
            assert auth["Authorization"][7:] not in caplog.text and entry["token"] not in caplog.text
    asyncio.run(check())


def test_twenty_mib_upload_and_download_are_streamed_with_accounted_peak_memory(tmp_path, monkeypatch, record_property):
    chunk = b"x" * CHUNK_SIZE
    expected = hashlib.sha256()
    for _ in range(320):
        expected.update(chunk)
    accounting = {"total": 0, "largest": 0, "chunks": 0}
    original = UploadFile.write
    def accounted(self, value):
        accounting["total"] += len(value)
        accounting["largest"] = max(accounting["largest"], len(value))
        accounting["chunks"] += 1
        return original(self, value)
    monkeypatch.setattr(UploadFile, "write", accounted)
    async def generated():
        for _ in range(320):
            yield chunk
    async def check():
        async with media_application(tmp_path) as (app, client):
            auth = bearer(app)
            data = FormData()
            data.add_field("file", generated(), filename="synthetic-private-phone-file.bin", content_type="application/octet-stream")
            tracemalloc.start()
            try:
                response = await client.post("/media/upload", headers=auth, data=data)
                result = await response.json()
                _, peak = tracemalloc.get_traced_memory()
            finally:
                tracemalloc.stop()
            assert response.status == 200
            assert peak < 8 * 1024 * 1024
            assert result["size"] == accounting["total"] == 20 * 1024 * 1024
            assert accounting["largest"] <= CHUNK_SIZE and accounting["chunks"] >= 320
            assert result["sha256"] == expected.hexdigest()
            for key, value in {"upload_peak_bytes": peak, "streamed_bytes": accounting["total"],
                               "largest_chunk_bytes": accounting["largest"], "chunk_count": accounting["chunks"]}.items():
                record_property(key, value)
            files = list(app[RUNTIME].media.paths.media_dir.iterdir())
            assert len(files) == 1 and files[0].name.startswith("atomic-upload-")
            assert "synthetic-private-phone-file" not in files[0].name
            assert stat.S_IMODE(app[RUNTIME].media.paths.media_dir.stat().st_mode) == 0o700
            response = await client.get("/media/" + result["token"], headers=auth)
            digest, count = hashlib.sha256(), 0
            async for piece in response.content.iter_chunked(CHUNK_SIZE):
                count += len(piece)
                digest.update(piece)
            assert response.status == 200 and count == result["size"] and digest.hexdigest() == expected.hexdigest()
            assert response.headers["Content-Type"] == "application/octet-stream" and response.headers["Cache-Control"] == "private"
        assert not files[0].exists()
    asyncio.run(check())


def test_cap_exceeded_is_413_and_leaves_no_file(tmp_path):
    async def check():
        async with media_application(tmp_path, cap=1024) as (app, client):
            response = await client.post("/media/upload", headers=bearer(app), data=multipart(b"x" * (CHUNK_SIZE * 2)))
            assert response.status == 413
            assert list(app[RUNTIME].media.paths.media_dir.iterdir()) == []
            assert app[RUNTIME].media.entries == {}
    asyncio.run(check())


def test_registration_magic_sensitive_headers_and_no_private_logging(tmp_path, caplog, monkeypatch):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    async def check():
        async with media_application(tmp_path) as (app, client):
            auth = bearer(app)
            source = tmp_path / "allowed" / "synthetic-private-path"
            source.write_bytes(b"\x89PNG\r\n\x1a\nsynthetic")
            response = await client.post("/media/register", headers=admin_headers(app), json={"path": str(source), "content_type": "image/jpeg",
                                                                "file_name": "synthetic-private-label.png"})
            assert response.status == 200
            entry = await response.json()
            assert entry["size"] == source.stat().st_size and not entry["sensitive"]
            assert entry["sha256"] == hashlib.sha256(source.read_bytes()).hexdigest()
            route = "/media/" + entry["token"]
            response = await client.get(route, headers={**auth, "X-Forwarded-For": "198.51.100.1"})
            assert response.status == 200 and await response.read() == source.read_bytes()
            assert response.headers["Content-Type"] == "image/png" and response.headers["Cache-Control"] == "private"
            assert "X-Media-Sensitive" not in response.headers
            assert (await client.post(route + "/sensitive", headers=admin_headers(app), json={})).status == 200
            response = await client.get(route, headers=auth)
            assert response.headers["X-Media-Sensitive"] == "1"
            await response.read()
            assert (await client.get(route)).status == 401
            session = app[RUNTIME].sessions.list_sessions()[0]
            session.grants["bridge"] = 1
            assert (await client.get(route, headers=auth)).status == 403
            assert (await client.post("/media/upload", headers=auth, data=multipart(b"x"))).status == 403
            session.grants["bridge"] = float("inf")
            source.write_bytes(b"not an image!!!!!!!!!")
            response = await client.get(route, headers=auth)
            assert response.status in {409, 415}
            leaked = entry["token"] in caplog.text
            assert not leaked
            assert str(source) not in caplog.text and "synthetic-private-label" not in caplog.text
    with caplog.at_level(logging.DEBUG):
        asyncio.run(check())


@pytest.mark.parametrize("header", ["X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "Forwarded", "X-Real-IP"])
def test_media_local_routes_use_shared_guard(tmp_path, header):
    async def check():
        async with media_application(tmp_path) as (_, client):
            for route in ("/media/register", "/media/synthetic-token/sensitive"):
                response = await client.post(route, headers={header: "synthetic"}, json={})
                assert response.status == 403 and await response.json() == {"ok": False, "error": "loopback only"}
    asyncio.run(check())


def test_register_bad_path_input_malformed_image_and_missing_media_are_visible(tmp_path):
    async def check():
        async with media_application(tmp_path) as (app, client):
            for body in ([], {}, {"path": "relative", "content_type": "text/plain"}):
                assert (await client.post("/media/register", headers=admin_headers(app), json=body)).status == 400
            assert (await client.post("/media/register", headers={**admin_headers(app), "Content-Type": "application/json"}, data="{invalid")).status == 400
            private = tmp_path / "allowed" / "protected"
            private.write_bytes(b"synthetic")
            app[RUNTIME].media.paths.denied.append(private)
            response = await client.post("/media/register", headers=admin_headers(app), json={"path": str(private), "content_type": "text/plain"})
            assert response.status == 403 and str(private) not in await response.text()
            auth = bearer(app)
            assert (await client.get("/media/synthetic-missing-token", headers=auth)).status == 404
            assert (await client.post("/media/synthetic-missing-token/sensitive", headers=admin_headers(app), json={})).status == 404
            image = tmp_path / "allowed" / "invalid-image"
            image.write_bytes(b"not-an-image")
            response = await client.post("/media/register", headers=admin_headers(app), json={"path": str(image), "content_type": "image/png"})
            result = await response.json()
            response = await client.get("/media/" + result["token"], headers=auth)
            assert response.status == 415
    asyncio.run(check())


def test_interrupted_download_is_an_incomplete_transfer_and_logs_no_private_detail(tmp_path, monkeypatch, caplog):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    private = "synthetic-private-download-error"
    async def check():
        async with media_application(tmp_path) as (app, client):
            source = tmp_path / "allowed" / "stream"
            source.write_bytes(b"x" * (CHUNK_SIZE * 2))
            entry = app[RUNTIME].media.register(source, "application/octet-stream")
            original = app[RUNTIME].media.open
            class Interrupted:
                def __init__(self, entry):
                    self.source, self.calls = original(entry), 0
                def __enter__(self):
                    return self
                def __exit__(self, *args):
                    self.source.close()
                def read(self, size):
                    self.calls += 1
                    if self.calls == 2:
                        raise OSError(private)
                    return self.source.read(size)
            monkeypatch.setattr(app[RUNTIME].media, "open", Interrupted)
            response = await client.get("/media/" + entry.token, headers=bearer(app))
            with pytest.raises(ClientPayloadError):
                await asyncio.wait_for(response.read(), timeout=2)
    with caplog.at_level(logging.ERROR):
        asyncio.run(check())
    assert private not in caplog.text and "operation=download interrupted" in caplog.text
