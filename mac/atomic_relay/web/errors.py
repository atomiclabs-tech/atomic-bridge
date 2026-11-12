"""Isolate unexpected failures at the HTTP request boundary."""

import logging

from aiohttp import web

logger = logging.getLogger("atomic_relay.web")


def sanitized_error(error):
    """Keep stack frames without arbitrary exception text or exception chains."""
    return RuntimeError, RuntimeError("operation failed"), error.__traceback__


def log_failure(target, error, context):
    target.exception("%s failure_type=%s", context, type(error).__name__, exc_info=sanitized_error(error))


@web.middleware
async def request_errors(request, handler):
    try:
        return await handler(request)
    except web.HTTPException:
        raise
    except Exception as error:
        resource = request.match_info.route.resource
        logger.exception("component=web operation=request route=%s failure_type=%s",
                         resource.canonical if resource else "unmatched", type(error).__name__, exc_info=sanitized_error(error))
        return web.json_response({"ok": False, "error": "request failed"}, status=500)
