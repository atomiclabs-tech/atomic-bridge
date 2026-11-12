# Ported from plugin/relay/server.py at upstream commit da782a8.
"""HTTP adapters for phone commands and loopback device inspection."""

import logging

from aiohttp import web

from atomic_relay.bridge.registry import BridgeError
from atomic_relay.web.errors import log_failure
from atomic_relay.web.runtime import RUNTIME

logger = logging.getLogger("atomic_relay.web")
BRIDGE_PATHS = {"/bridge/status", "/bridge/devices", "/bridge/select-active"}
SELECTORS = ("device", "device_id", "deviceId")
GET_PATHS = "/ping /screen /screenshot /get_apps /apps /current_app /clipboard /screen_hash /events /location /files".split()
POST_PATHS = ("/tap /tap_text /long_press /type /swipe /drag /open_app /return_to_hermes /press_key /scroll "
              "/describe_node /wait /setup /clipboard /media /find_nodes /diff_screen /send_intent /broadcast "
              "/events/stream /search_contacts /call /send_sms /share_media /send_mms /files/pull /files/push /call_audio_probe").split()


def _selector(values):
    choices = [values.pop(key, None) for key in SELECTORS]
    return next((value for value in choices if value), None)


def _error(error):
    log_failure(logger, error, "component=bridge operation=request refused")
    return web.json_response({"error": str(error)}, status=error.status_code)


async def command(request):
    params = dict(request.query)
    selector = _selector(params)
    try:
        body = await request.json() if request.method == "POST" else dict(params)
        if not isinstance(body, dict):
            raise ValueError("command body must be an object")
        body = dict(body)
        body_selector = _selector(body)
        if selector is None:
            selector = body_selector
        if selector is not None and not isinstance(selector, str):
            raise ValueError("device selector must be a string")
    except ValueError as error:
        log_failure(logger, error, "component=bridge operation=parse invalid request")
        return web.json_response({"error": "invalid command body or selector"}, status=400)
    try:
        response = await request.app[RUNTIME].dispatch.command(request.method, request.path, params, body, selector)
    except BridgeError as error:
        return _error(error)
    return web.json_response(response["result"], status=response["status"])


async def devices(request):
    return web.json_response(request.app[RUNTIME].registry.devices_payload())


async def status(request):
    selector = _selector(dict(request.query))
    try:
        payload = request.app[RUNTIME].registry.status_payload(selector)
    except BridgeError as error:
        if error.status_code == 503:
            return web.json_response({"phone_connected": False, "last_seen_seconds_ago": None,
                                      "error": "no phone connected"}, status=503)
        return _error(error)
    return web.json_response(payload)


async def select_active(request):
    try:
        body = await request.json() if request.body_exists else {}
        if not isinstance(body, dict):
            raise ValueError("body must be an object")
        selector = _selector(dict(body))
        if not isinstance(selector, str) or not selector.strip():
            raise ValueError("device selector required")
    except ValueError as error:
        log_failure(logger, error, "component=bridge operation=select invalid request")
        return web.json_response({"ok": False, "error": "missing device or device_id"}, status=400)
    try:
        return web.json_response(request.app[RUNTIME].registry.select_active(selector))
    except BridgeError as error:
        return _error(error)


def add_routes(app):
    for path in GET_PATHS:
        app.router.add_get(path, command)
    for path in POST_PATHS:
        app.router.add_post(path, command)
    app.router.add_get("/bridge/status", status)
    app.router.add_get("/bridge/devices", devices)
    app.router.add_post("/bridge/select-active", select_active)
