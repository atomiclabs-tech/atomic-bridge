# Ported from plugin/relay/server.py at upstream commit da782a8.
"""Operator session listing, revocation and reduce-only policy changes."""

import logging
import math

from aiohttp import web

from atomic_relay.sessions.grants import duration, validate_grants
from atomic_relay.web.runtime import RUNTIME

logger = logging.getLogger("atomic_relay.web")
SESSION_PATHS = {"/sessions", "/sessions/{token_prefix}"}


def _public(session):
    fields = ("device_name", "device_id", "created_at", "first_seen", "last_seen",
              "transport_hint", "client_surface", "device_form_factor", "device_model", "device_platform")
    result = {key: getattr(session, key) for key in fields}
    result.update(token_prefix=session.token[:8], expires_at=None if session.expires_at == math.inf else session.expires_at,
                  grants={key: None if value == math.inf else value for key, value in session.grants.items()},
                  is_current=False)
    if session.supervised_mode is not None:
        result["supervised_mode"] = session.supervised_mode
    return result


def _target(request):
    prefix = request.match_info["token_prefix"].strip()
    if len(prefix) < 8:
        raise web.HTTPBadRequest(text='{"error": "token prefix too short"}', content_type="application/json")
    matches = request.app[RUNTIME].sessions.find_by_prefix(prefix)
    if not matches:
        raise web.HTTPNotFound(text="no session matches prefix")
    if len(matches) > 1:
        raise web.HTTPConflict(text="multiple sessions match prefix; retry with more characters")
    return matches[0]


async def list_sessions(request):
    entries = [_public(item) for item in request.app[RUNTIME].sessions.list_sessions()]
    entries.sort(key=lambda item: item["last_seen"], reverse=True)
    return web.json_response({"sessions": entries})


async def revoke(request):
    target = _target(request)
    runtime = request.app[RUNTIME]
    runtime.sessions.revoke_session(target.token)
    await runtime.close_session_sockets(target.token)
    return web.json_response({"ok": True, "revoked": target.token[:8],
                              "revoked_self": False})


async def reduce(request):
    try:
        payload = await request.json()
        if not isinstance(payload, dict):
            raise ValueError("body must be a JSON object")
        ttl, grants = payload.get("ttl_seconds"), payload.get("grants")
        if ttl is None and grants is None:
            raise ValueError("must provide ttl_seconds or grants")
        if ttl is not None:
            duration(ttl, whole=True)
        if grants is not None:
            validate_grants(grants)
        target = _target(request)
        updated = request.app[RUNTIME].sessions.reduce_session_policy(target.token, ttl_seconds=ttl, grants=grants)
    except ValueError:
        logger.exception("component=sessions operation=reduce invalid request")
        return web.json_response({"ok": False, "error": "invalid session policy"}, status=400)
    except PermissionError as error:
        logger.exception("component=sessions operation=reduce policy expansion denied")
        raise web.HTTPForbidden(text="operator approval required to expand session policy") from error
    if updated is None:
        raise web.HTTPNotFound(text="session vanished mid-update")
    public = _public(updated)
    return web.json_response({"ok": True, **{key: public[key] for key in ("token_prefix", "expires_at", "grants")}})


def add_routes(app):
    app.router.add_get("/sessions", list_sessions)
    app.router.add_delete("/sessions/{token_prefix}", revoke)
    app.router.add_patch("/sessions/{token_prefix}", reduce)
