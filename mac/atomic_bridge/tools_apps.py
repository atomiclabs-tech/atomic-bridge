# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Application, intent, event, clipboard and media commands."""

from .tool_support import ToolInputError, call, logger, optional, required, tool_handler


@tool_handler
def android_open_app(args: dict, **kwargs):
    return call("POST", "/open_app", args, {"package": required(args, "package")})


@tool_handler
def android_get_apps(args: dict, **kwargs):
    return call("GET", "/apps", args)


@tool_handler
def android_send_intent(args: dict, **kwargs):
    values = optional(args, ("data", "package", "component", "extras", "category"), {
        "action": required(args, "action"),
    })
    return call("POST", "/send_intent", args, values)


@tool_handler
def android_broadcast(args: dict, **kwargs):
    values = optional(args, ("data", "package", "extras"), {"action": required(args, "action")})
    return call("POST", "/broadcast", args, values)


@tool_handler
def android_events(args: dict, **kwargs):
    limit, since = args.get("limit", 50), args.get("since", 0)
    if isinstance(limit, bool) or not isinstance(limit, int) or limit < 1:
        raise ToolInputError("limit must be a positive integer")
    if isinstance(since, bool) or not isinstance(since, int) or since < 0:
        raise ToolInputError("since must be a non-negative integer")
    if limit > 500:
        logger.warning("component=tools operation=android_events limit clamped to supported range")
    return call("GET", "/events", args, {"limit": min(limit, 500), "since": since})


@tool_handler
def android_event_stream(args: dict, **kwargs):
    enabled = required(args, "enabled")
    if not isinstance(enabled, bool):
        raise ToolInputError("enabled must be a boolean")
    return call("POST", "/events/stream", args, {"enabled": enabled})


@tool_handler
def android_clipboard_read(args: dict, **kwargs):
    return call("GET", "/clipboard", args)


@tool_handler
def android_clipboard_write(args: dict, **kwargs):
    return call("POST", "/clipboard", args, {"text": required(args, "text")})


@tool_handler
def android_media(args: dict, **kwargs):
    action = required(args, "action")
    if not isinstance(action, str) or action.strip().lower() not in ("play", "pause", "toggle", "next", "previous"):
        raise ToolInputError("action must be play, pause, toggle, next or previous")
    return call("POST", "/media", args, {"action": action.strip().lower()})


HANDLERS = {function.__name__: function for function in (
    android_open_app, android_get_apps, android_send_intent, android_broadcast,
    android_events, android_event_stream, android_clipboard_read, android_clipboard_write, android_media,
)}
