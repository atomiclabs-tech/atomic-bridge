# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import logging

from aiohttp import MultipartWriter

from atomic_relay.web import media_upload
from atomic_relay.web.runtime import RUNTIME
from media_cases import media_application, multipart


def test_upload_shape_failures_and_skipped_non_file_field(tmp_path):
    async def check():
        async with media_application(tmp_path) as (app, client):
            session = app[RUNTIME].sessions.create_session("Synthetic", "device", ttl_seconds=0)
            auth = {"Authorization": f"Bearer {session.token}"}
            assert (await client.post("/media/upload", data=multipart(b"x"))).status == 401
            assert (await client.post("/media/upload", headers=auth, data=b"not multipart")).status == 400
            empty = MultipartWriter("form-data")
            assert (await client.post("/media/upload", headers=auth, data=empty)).status == 400
            fields = multipart(b"skip", name="unused")
            fields.add_field("file", b"kept", filename="synthetic", content_type="text/plain")
            response = await client.post("/media/upload", headers=auth, data=fields)
            assert response.status == 200 and (await response.json())["size"] == 4
    asyncio.run(check())


def test_upload_write_failure_is_private_cleans_file_and_next_upload_succeeds(tmp_path, monkeypatch, caplog):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    private = "synthetic-private-write-detail"
    async def check():
        async with media_application(tmp_path) as (app, client):
            session = app[RUNTIME].sessions.create_session("Synthetic", "device", ttl_seconds=0)
            auth = {"Authorization": f"Bearer {session.token}"}
            original = media_upload.UploadFile.write
            def failed(self, chunk):
                raise OSError(private)
            monkeypatch.setattr(media_upload.UploadFile, "write", failed)
            response = await client.post("/media/upload", headers=auth, data=multipart(b"synthetic"))
            assert response.status == 400 and private not in await response.text()
            assert list(app[RUNTIME].media.paths.media_dir.iterdir()) == []
            monkeypatch.setattr(media_upload.UploadFile, "write", original)
            assert (await client.post("/media/upload", headers=auth, data=multipart(b"synthetic"))).status == 200
    with caplog.at_level(logging.ERROR):
        asyncio.run(check())
    assert private not in caplog.text and "synthetic-private-phone-file" not in caplog.text
    assert any(row.exc_info for row in caplog.records)
