# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Argument handling and failure isolation shared by phone tools."""

import logging
from functools import wraps

from .client import RelayClient
from .result import json_result

logger = logging.getLogger("atomic_bridge")


class ToolInputError(ValueError):
    """An argument error containing fixed explanatory text only."""


class ToolFailure(ValueError):
    """A structured failure that must reach the caller unchanged."""

    def __init__(self, value):
        super().__init__("Phone tool failed")
        self.value = value


def failure_info(exc):
    safe = RuntimeError(f"{type(exc).__name__} during phone tool")
    return RuntimeError, safe, exc.__traceback__


def log_failure(operation, exc):
    logger.error("component=tools operation=%s failed", operation,
                 exc_info=failure_info(exc))


def tool_handler(function):
    """Isolate each invocation without logging arguments or dependency messages."""
    @wraps(function)
    def handler(args: dict, **kwargs):
        try:
            if not isinstance(args, dict):
                raise ToolInputError("Arguments must be an object")
            return function(dict(args), **kwargs)
        except ToolFailure as exc:
            log_failure(function.__name__, exc)
            return json_result(exc.value)
        except ToolInputError as exc:
            log_failure(function.__name__, exc)
            return json_result({"error": str(exc)})
        except Exception as exc:
            logger.exception("component=tools operation=%s failed", function.__name__, exc_info=failure_info(exc))
            return json_result({"error": f"Phone tool failed: {type(exc).__name__}"})
    return handler


def required(args, key):
    if key not in args:
        raise ToolInputError(f"Missing required argument: {key}")
    return args[key]


def selected(args, values=None):
    result = dict(values or {})
    if "device" in args:
        result["device"] = args["device"]
    return result


def optional(args, keys, values=None):
    result = dict(values or {})
    result.update({key: args[key] for key in keys if args.get(key) is not None})
    return result


def call(method, path, args, values=None):
    return json_result(RelayClient().request(method, path, selected(args, values)).value)
