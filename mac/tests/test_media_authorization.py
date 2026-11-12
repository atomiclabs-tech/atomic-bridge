from session_cases import credential_log_guard as credential_log_guard

from types import SimpleNamespace

import pytest
from aiohttp import web

from atomic_relay.sessions.manager import SessionManager
from atomic_relay.sessions.admin import AdminCredential
from atomic_relay.web.guard import ADMIN_SECRET
from atomic_relay.web.media_authorization import authorize
from atomic_relay.web.runtime import RUNTIME


def test_media_requires_existing_bearer_with_live_bridge_grant():
    sessions = SessionManager()
    session = sessions.create_session("Synthetic", "device", ttl_seconds=0)
    request = SimpleNamespace(headers={}, app={RUNTIME: SimpleNamespace(sessions=sessions),
                                              ADMIN_SECRET: AdminCredential.ephemeral()})
    for value in ("", "Basic synthetic", "Bearer ", "Bearer synthetic-invalid"):
        request.headers = {"Authorization": value}
        with pytest.raises(web.HTTPUnauthorized):
            authorize(request)
    request.headers = {"Authorization": f"Bearer {session.token}"}
    assert authorize(request) is session
    session.grants["bridge"] = 1
    with pytest.raises(web.HTTPForbidden):
        authorize(request)


def test_admin_media_authorization_precedes_every_session_lookup(monkeypatch):
    import secrets
    secret = secrets.token_urlsafe(32)
    sessions = SessionManager()
    def forbidden_lookup(token):
        pytest.fail("admin authorization must not look up a phone session")
    monkeypatch.setattr(sessions, "get_session", forbidden_lookup)
    request = SimpleNamespace(headers={"Authorization": "Bearer " + secret},
                              app={RUNTIME: SimpleNamespace(sessions=sessions), ADMIN_SECRET: AdminCredential(secret)})
    assert authorize(request) is None
