"""Shared transport, browser-shape and credential checks for operator routes."""

import logging
from collections.abc import Awaitable, Callable, Collection

from aiohttp import web

from atomic_relay.sessions.admin import AdminCredential

ADMIN_SECRET = web.AppKey("admin_secret", AdminCredential)
FORWARDED_HEADERS = (
    "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "Forwarded", "X-Real-IP",
)
logger = logging.getLogger("atomic_relay.web")
OK_PATHS = {
    "/pairing/register", "/pairing/approve", "/pairing/check", "/bridge/select-active", "/notifications/recent",
    "/notifications/wait", "/sessions/{token_prefix}", "/media/register", "/media/{token}/sensitive",
}


def _peer(request: web.Request) -> str | None:
    transport = request.transport
    address = transport.get_extra_info("peername") if transport is not None else None
    return address[0] if isinstance(address, tuple) and address else None


def is_loopback(request: web.Request) -> bool:
    """Require a loopback transport peer and absence of every forwarded header."""
    return _peer(request) in {"127.0.0.1", "::1"} and not any(
        header in request.headers for header in FORWARDED_HEADERS
    )


def _canonical(request: web.Request) -> str:
    resource = request.match_info.route.resource
    return resource.canonical if resource is not None else "unmatched"


def _refusal(request, reason, status, *, admin=False):
    body = {"error": reason}
    if admin and _canonical(request) in OK_PATHS:
        body = {"ok": False, **body}
    return web.json_response(body, status=status)


def _loopback_refusal(request, *, admin=False):
    if is_loopback(request):
        return None
    names = [name for name in FORWARDED_HEADERS if name in request.headers]
    logger.warning("component=guard operation=authorize refused route=%s peer=%s headers=%s",
                   _canonical(request), _peer(request), names)
    return _refusal(request, "loopback only", 403, admin=admin)


def refuse_origin(request, *, admin=False):
    """Refuse before reading a request body or preparing a websocket upgrade."""
    if "Origin" not in request.headers:
        return None
    logger.warning("component=guard operation=origin refused route=%s peer=%s", _canonical(request), _peer(request))
    return _refusal(request, "browser origin refused", 403, admin=admin)


def bearer(request) -> str:
    header = request.headers.get("Authorization", "")
    return header[7:] if header.startswith("Bearer ") else ""


def has_admin(request) -> bool:
    return request.app[ADMIN_SECRET].compare(bearer(request))


def require_admin(request):
    if has_admin(request):
        return None
    logger.warning("component=guard operation=admin refused route=%s peer=%s", _canonical(request), _peer(request))
    return _refusal(request, "admin credential required", 401, admin=True)


def _selected(request, protected):
    return request.path in protected or _canonical(request) in protected


def loopback_middleware(paths: Collection[str]) -> Callable:
    """Protect listed paths or route templates; other routes keep their access policy."""
    protected = frozenset(paths)

    @web.middleware
    async def guard(request: web.Request, handler: Callable[[web.Request], Awaitable[web.StreamResponse]]):
        if _selected(request, protected):
            refusal = _loopback_refusal(request)
            if refusal is not None:
                return refusal
        return await handler(request)

    return guard


def admin_middleware(paths: Collection[str]) -> Callable:
    """Apply transport, Origin, JSON and admin checks in that order."""
    protected = frozenset(paths)

    @web.middleware
    async def guard(request, handler):
        if _selected(request, protected):
            for check in (_loopback_refusal, refuse_origin):
                refusal = check(request, admin=True)
                if refusal is not None:
                    return refusal
            body_required = request.method in {"POST", "PATCH"} or (request.method == "DELETE" and request.body_exists)
            if body_required and request.content_type != "application/json":
                logger.warning("component=guard operation=content-type refused route=%s peer=%s", _canonical(request), _peer(request))
                return _refusal(request, "application/json required", 415, admin=True)
            refusal = require_admin(request)
            if refusal is not None:
                return refusal
        return await handler(request)

    return guard
