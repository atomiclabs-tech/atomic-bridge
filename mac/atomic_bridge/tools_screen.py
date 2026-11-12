# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Screen inspection and screenshot commands for Atomic."""

import base64
import logging

from .result import json_result, multimodal_result
from .screenshot_media import ScreenshotMediaError, resolve_screenshot
from .tool_support import RelayClient, call, failure_info, log_failure, optional, required, selected, tool_handler

logger = logging.getLogger("atomic_bridge")


@tool_handler
def android_ping(args: dict, **kwargs):
    return call("GET", "/ping", args)


@tool_handler
def android_read_screen(args: dict, **kwargs):
    return call("GET", "/screen", args, {"include_bounds": str(args.get("include_bounds", False)).lower()})


@tool_handler
def android_find_nodes(args: dict, **kwargs):
    values = optional(args, ("text", "class_name", "clickable"), {"limit": int(args.get("limit", 20))})
    return call("POST", "/find_nodes", args, values)


@tool_handler
def android_describe_node(args: dict, **kwargs):
    return call("POST", "/describe_node", args, {"nodeId": required(args, "node_id")})


@tool_handler
def android_screen_hash(args: dict, **kwargs):
    return call("GET", "/screen_hash", args)


@tool_handler
def android_diff_screen(args: dict, **kwargs):
    return call("POST", "/diff_screen", args, {"previous_hash": required(args, "previous_hash")})


@tool_handler
def android_current_app(args: dict, **kwargs):
    return call("GET", "/current_app", args)


def android_screenshot(args: dict, **kwargs):
    stage = "command"
    try:
        client = RelayClient()
        command = client.request("GET", "/screenshot", selected(args))
        value = command.value
        if command.status != 200 or value.get("success") is False or value.get("error"):
            fields = {key: item for key, item in value.items() if key != "error"}
            if command.status is not None and command.status != 200:
                fields["status"] = command.status
            raise ScreenshotMediaError(value.get("error", "Screenshot command failed"), "command", **fields)
        stage = "fetch"
        image = resolve_screenshot(value, client)
        stage = "command"
        if args.get("sensitive", False):
            if image.marker is None:
                raise ScreenshotMediaError("Screenshot has no media token for sensitive marking", "command")
            token = image.marker.removeprefix("MEDIA:hermes-relay://")
            response = client.request("POST", f"/media/{token}/sensitive", {}, timeout=5)
            if response.status != 200 or response.value.get("error") or response.value.get("ok") is False:
                fields = {key: item for key, item in response.value.items() if key != "error"}
                if response.status is not None:
                    fields["status"] = response.status
                raise ScreenshotMediaError(response.value.get("error", "Sensitive screenshot registration failed"), "command", **fields)
        stage = "decode"
        summary = f"Screenshot captured: {image.width}x{image.height}, {len(image.data)} bytes."
        if image.marker is not None:
            summary += "\n" + image.marker
        if args.get("sensitive", False):
            summary += "\nSensitive screen: handle privately."
        encoded = base64.b64encode(image.data).decode("ascii")
        return multimodal_result([
            {"type": "text", "text": summary},
            {"type": "image_url", "image_url": {"url": f"data:{image.content_type};base64,{encoded}"}},
        ], summary)
    except ScreenshotMediaError as exc:
        log_failure("android_screenshot", exc)
        return json_result(exc.value)
    except Exception as exc:
        logger.exception("component=tools operation=android_screenshot failed", exc_info=failure_info(exc))
        return json_result({"error": f"Screenshot failed: {type(exc).__name__}", "stage": stage})


HANDLERS = {function.__name__: function for function in (
    android_ping, android_read_screen, android_find_nodes, android_describe_node,
    android_screen_hash, android_diff_screen, android_current_app, android_screenshot,
)}
