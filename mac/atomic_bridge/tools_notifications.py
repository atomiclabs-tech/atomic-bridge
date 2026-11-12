# Adapted from plugin/tools/android_notifications.py at upstream commit da782a8.
"""Read and wait for Atomic notifications through the relay client."""

import logging
import math
import time

from .client import RelayClient
from .result import json_result
from .tool_support import ToolInputError, optional, selected, tool_handler

logger = logging.getLogger("atomic_bridge")


def _failed(response):
    return (response.status != 200 or "error" in response.value
            or response.value.get("success") is False or response.value.get("ok") is False)


@tool_handler
def android_notifications_recent(args: dict, **kwargs):
    limit = args.get("limit", 20)
    if isinstance(limit, bool) or not isinstance(limit, int) or limit < 1:
        raise ToolInputError("limit must be a positive integer")
    values = optional(args, ("package", "text"), {"limit": limit})
    minutes = args.get("since_minutes")
    if minutes is not None:
        if isinstance(minutes, bool) or not isinstance(minutes, (int, float)) or not math.isfinite(minutes) or minutes < 0:
            raise ToolInputError("since_minutes must be a non-negative finite number")
        since = (time.time() - minutes * 60) * 1000
        if not math.isfinite(since):
            raise ToolInputError("since_minutes exceeds the supported timestamp range")
        values["since"] = int(since)
    response = RelayClient().request("GET", "/notifications/recent", selected(args, values))
    if _failed(response):
        return json_result(response.value)
    notifications, count = response.value.get("notifications"), response.value.get("count")
    if response.value.get("ok") is not True or not isinstance(notifications, list) or type(count) is not int or count != len(notifications):
        raise ToolInputError("Notification list response is malformed")
    return json_result({"status": "ok", "count": count, "notifications": notifications})


@tool_handler
def android_wait_notification(args: dict, **kwargs):
    timeout = args.get("timeout_s", 60)
    if isinstance(timeout, bool) or not isinstance(timeout, int):
        raise ToolInputError("timeout_s must be an integer")
    bounded = max(1, min(120, timeout))
    if bounded != timeout:
        logger.warning("component=tools operation=android_wait_notification timeout clamped to supported range")
    values = optional(args, ("package", "text"), {"timeout_s": bounded})
    response = RelayClient().request(
        "GET", "/notifications/wait", selected(args, values), timeout=bounded + 10,
    )
    if _failed(response):
        return json_result(response.value)
    value = response.value
    if value.get("ok") is True and value.get("matched") is True and isinstance(value.get("notification"), dict):
        return json_result(value["notification"])
    waited = value.get("waited_s")
    if (value.get("ok") is True and value.get("matched") is False and not isinstance(waited, bool)
            and isinstance(waited, (int, float)) and math.isfinite(waited) and waited >= 0):
        return json_result({"matched": False, "waited_s": waited})
    raise ToolInputError("Notification wait response is malformed")


HANDLERS = {"android_notifications_recent": android_notifications_recent,
            "android_wait_notification": android_wait_notification}
