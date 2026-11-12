# Ported from plugin/relay/server.py at upstream commit da782a8.
"""Accept the admin bearer first, otherwise a session with a live bridge grant."""

from aiohttp import web

from atomic_relay.web.guard import has_admin
from atomic_relay.web.runtime import RUNTIME


def authorize(request):
    if has_admin(request):
        return None
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer ") or not header[7:].strip():
        raise web.HTTPUnauthorized(text="Authorization: Bearer session token required")
    session = request.app[RUNTIME].sessions.get_session(header[7:].strip())
    if session is None:
        raise web.HTTPUnauthorized(text="invalid or expired session token")
    if session.channel_is_expired("bridge"):
        raise web.HTTPForbidden(text="active bridge grant required")
    return session
