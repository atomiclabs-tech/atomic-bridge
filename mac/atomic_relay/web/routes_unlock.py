"""Forward one unlock command using only the relay's configured PIN file."""

import logging

from aiohttp import web

from atomic_relay.bridge.registry import BridgeError
from atomic_relay.sessions.unlock_pin import UnlockPin, UnlockPinError
from atomic_relay.web.errors import log_failure
from atomic_relay.web.routes_bridge import error_response, extract_selector
from atomic_relay.web.runtime import RUNTIME

logger = logging.getLogger("atomic_relay.unlock")
UNLOCK_PATHS = {"/unlock"}


async def unlock(request):
    params = dict(request.query)
    try:
        body = await request.json()
        if not isinstance(body, dict):
            raise ValueError("command body must be an object")
        if "pin" in params or "pin" in body:
            return web.json_response({"error": "pin is supplied by the relay"}, status=400)
        selector = extract_selector(params)
        body_selector = extract_selector(body)
        if selector is None:
            selector = body_selector
        if selector is not None and not isinstance(selector, str):
            raise ValueError("device selector must be a string")
    except ValueError as error:
        log_failure(logger, error, "component=unlock operation=parse invalid request")
        return web.json_response({"error": "invalid command body or selector"}, status=400)
    runtime = request.app[RUNTIME]
    if runtime.unlock_pin_file is None:
        return web.json_response({"error": "unlock PIN not configured"}, status=503)
    try:
        pin = UnlockPin.load(runtime.unlock_pin_file)
    except UnlockPinError as error:
        logger.warning("component=unlock operation=read reason=%s", error)
        return web.json_response({"error": "unlock PIN unavailable"}, status=503)
    try:
        response = await runtime.dispatch.command("POST", "/unlock", params, {"pin": pin}, selector)
    except BridgeError as error:
        return error_response(error)
    return web.json_response(response["result"], status=response["status"])


def add_routes(app):
    app.router.add_post("/unlock", unlock)
