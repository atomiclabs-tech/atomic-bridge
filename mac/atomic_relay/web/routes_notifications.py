# Ported from plugin/relay/server.py at upstream commit da782a8.
"""Loopback readers and concurrent waits for memory-only notification events."""

import logging
import math

from aiohttp import web

from atomic_relay.bridge.registry import BridgeError
from atomic_relay.web.errors import log_failure
from atomic_relay.web.runtime import RUNTIME

logger = logging.getLogger("atomic_relay.notifications")
NOTIFICATION_PATHS = {"/notifications/recent", "/notifications/wait"}


def _number(value, minimum, maximum):
    number = float(value)
    if not math.isfinite(number) or not minimum <= number <= maximum:
        raise ValueError("notification query number out of range")
    return number


def _filters(request):
    query, runtime = request.query, request.app[RUNTIME]
    device = None
    selector = query.get("device") or query.get("device_id") or query.get("deviceId")
    if selector:
        try:
            device = runtime.registry.resolve(selector, require_connected=False)[0].device_id
        except BridgeError as error:
            if error.status_code != 404:
                raise
            device = runtime.notifications.select_device(selector)
    result = {"device": device}
    if "package" in query:
        result["package"] = query["package"]
    if "text" in query:
        result["text"] = query["text"]
    if "since" in query:
        result["since"] = _number(query["since"], 0, float("inf"))
    return result


def _error(error):
    log_failure(logger, error, "component=notifications operation=query")
    status = error.status_code if isinstance(error, BridgeError) else 400
    return web.json_response({"ok": False, "error": "invalid notification query"}, status=status)


async def recent(request):
    try:
        limit = int(request.query.get("limit", "20"))
        if not 1 <= limit <= 500:
            raise ValueError("notification limit out of range")
        entries = request.app[RUNTIME].notifications.recent(limit=limit, **_filters(request))
    except (ValueError, BridgeError) as error:
        return _error(error)
    return web.json_response({"ok": True, "notifications": entries, "count": len(entries)})


async def wait(request):
    runtime = request.app[RUNTIME]
    try:
        filters = _filters(request)
        filters.setdefault("since", runtime.notifications.clock() * 1000)
        timeout = _number(request.query.get("timeout_s", "60"), 1, 120)
    except (ValueError, BridgeError) as error:
        return _error(error)
    return web.json_response(await runtime.notification_waiters.wait(timeout_s=timeout, **filters))


def add_routes(app):
    app.router.add_get("/notifications/recent", recent)
    app.router.add_get("/notifications/wait", wait)
