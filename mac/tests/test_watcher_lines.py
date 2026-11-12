# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import logging
import re

import pytest
from aiohttp import WSServerHandshakeError

from atomic_relay.logsetup import configure_logging
from atomic_relay.web.runtime import RUNTIME
from fake_phone import FakePhone, session_payload
from session_cases import application


def test_watcher_messages_and_logger_names_have_exact_configured_line_format(tmp_path):
    logger = logging.getLogger("atomic_relay")
    previous = logger.handlers[:], logger.level, logger.propagate
    logger.handlers = []
    path = tmp_path / "watcher.log"
    configure_logging(path, "DEBUG")
    async def check():
        async with application(tmp_path) as (app, client):
            app[RUNTIME].trusted_proxies = frozenset({"127.0.0.1"})
            for _ in range(5):
                phone = await FakePhone.connect(client, {"session_token": "synthetic-invalid"},
                                                headers={"X-Forwarded-For": "198.51.100.8"}, expected="auth.fail")
                await phone.close()
            with pytest.raises(WSServerHandshakeError) as error:
                await asyncio.wait_for(client.ws_connect("/ws", headers={"X-Forwarded-For": "198.51.100.8"}), timeout=2)
            assert error.value.status == 429
            session = app[RUNTIME].sessions.create_session("Synthetic private name", "watcher-device", ttl_seconds=0)
            phone = await FakePhone.connect(client, session_payload(session))
            await phone.status({"current_app": "synthetic-private-status"})
            await phone.close()
    try:
        asyncio.run(check())
        for handler in logger.handlers:
            handler.flush()
        text = path.read_text()
        prefix = r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d,\d{3} "
        assert len(re.findall(prefix + r"INFO atomic_relay: Auth failed$", text, re.MULTILINE)) == 5
        assert re.search(prefix + r"WARNING atomic_relay: WebSocket from blocked IP 198\.51\.100\.8 — rejecting$", text, re.MULTILINE)
        assert re.search(prefix + r"INFO atomic_relay\.bridge: bridge: detached device watcher-device \(client 127\.0\.0\.1 disconnected\)$", text, re.MULTILINE)
        assert "synthetic-invalid" not in text and "Synthetic private name" not in text and "synthetic-private-status" not in text
    finally:
        for handler in logger.handlers:
            handler.close()
        logger.handlers, logger.level, logger.propagate = previous
