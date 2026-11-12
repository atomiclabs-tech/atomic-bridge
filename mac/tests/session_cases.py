import asyncio
import secrets
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
from aiohttp import ClientTimeout
from aiohttp.test_utils import TestClient, TestServer

from atomic_relay.config import load_config
from atomic_relay.sessions.admin import AdminCredential
from atomic_relay.sessions.manager import SessionManager
from atomic_relay.web.app import create_app
from atomic_relay.web.guard import ADMIN_SECRET

FIXTURE = Path(__file__).parent / "fixtures" / "sessions_v1_sample.json"


@pytest.fixture(autouse=True)
def credential_log_guard(caplog, monkeypatch):
    """Check every refusal, including checks before the bearer comparison runs."""
    credentials = set()
    original_init, original_compare = AdminCredential.__init__, AdminCredential.compare
    original_create = SessionManager.create_session
    def initialize(self, secret, **kwargs):
        credentials.add(secret)
        original_init(self, secret, **kwargs)
    def compare(self, presented):
        if presented:
            credentials.add(presented)
        return original_compare(self, presented)
    def create(self, *args, **kwargs):
        session = original_create(self, *args, **kwargs)
        credentials.update(value for value in (session.token, session.refresh_token) if value)
        return session
    monkeypatch.setattr(AdminCredential, "__init__", initialize)
    monkeypatch.setattr(AdminCredential, "compare", compare)
    monkeypatch.setattr(SessionManager, "create_session", create)
    yield
    assert "Bearer " not in caplog.text
    leaked = any(value in caplog.text for value in credentials if len(value) >= 8)
    assert not leaked, "A credential appeared in captured logs"


def configuration(tmp_path, sessions=""):
    secret_file = tmp_path / "admin.secret"
    if not secret_file.exists():
        secret_file.write_text(secrets.token_urlsafe(32) + "\n")
        secret_file.chmod(0o600)
    return load_config(environ={
        "ATOMIC_RELAY_PORT": "0", "ATOMIC_RELAY_SESSIONS_FILE": str(sessions),
        "ATOMIC_RELAY_LOG_FILE": str(tmp_path / "relay.log"),
        "ATOMIC_RELAY_MEDIA_DIR": str(tmp_path / "media"),
        "ATOMIC_RELAY_ADMIN_SECRET_FILE": str(secret_file),
    })


def admin_headers(app):
    return {"Authorization": app[ADMIN_SECRET].authorization()}


def cli_env(tmp_path):
    configuration(tmp_path)
    env_file = tmp_path / "relay.env"
    env_file.write_text("\n".join((
        "ATOMIC_RELAY_SESSIONS_FILE=",
        "ATOMIC_RELAY_ADMIN_SECRET_FILE=" + str(tmp_path / "admin.secret"),
        "ATOMIC_RELAY_LOG_FILE=" + str(tmp_path / "cli.log"),
        "ATOMIC_RELAY_MEDIA_DIR=" + str(tmp_path / "cli-media"),
    )) + "\n")
    return env_file
@asynccontextmanager
async def application(tmp_path, sessions="", *, admin=False):
    app = create_app(configuration(tmp_path, sessions))
    client = TestClient(TestServer(app, port=0), timeout=ClientTimeout(total=2),
                        headers={**admin_headers(app), "Content-Type": "application/json"} if admin else None)
    await asyncio.wait_for(client.start_server(), timeout=2)
    try:
        yield app, client
    finally:
        await asyncio.wait_for(client.close(), timeout=2)
