# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Touch, gesture, text and key commands for Atomic."""

from .tool_support import ToolInputError, call, logger, required, tool_handler


@tool_handler
def android_tap(args: dict, **kwargs):
    if args.get("node_id"):
        values = {"nodeId": args["node_id"]}
    elif args.get("x") is not None and args.get("y") is not None:
        values = {"x": args["x"], "y": args["y"]}
    else:
        raise ToolInputError("Provide either x and y or node_id")
    return call("POST", "/tap", args, values)


@tool_handler
def android_tap_text(args: dict, **kwargs):
    return call("POST", "/tap_text", args, {"text": required(args, "text"), "exact": args.get("exact", False)})


@tool_handler
def android_long_press(args: dict, **kwargs):
    coordinates = args.get("x") is not None and args.get("y") is not None
    node = args.get("node_id") is not None and str(args["node_id"]).strip() != ""
    if coordinates == node:
        raise ToolInputError("Provide either x and y or node_id, exclusively")
    duration = args.get("duration", 500)
    if isinstance(duration, bool) or not isinstance(duration, int) or not 100 <= duration <= 3000:
        raise ToolInputError("duration must be an integer from 100 to 3000 ms")
    values = {"duration": duration}
    values.update({"node_id": args["node_id"]} if node else {"x": args["x"], "y": args["y"]})
    return call("POST", "/long_press", args, values)


@tool_handler
def android_type(args: dict, **kwargs):
    return call("POST", "/type", args, {
        "text": required(args, "text"), "clearFirst": args.get("clear_first", False),
    })


@tool_handler
def android_swipe(args: dict, **kwargs):
    return call("POST", "/swipe", args, {
        "direction": required(args, "direction"), "distance": args.get("distance", "medium"),
    })


@tool_handler
def android_drag(args: dict, **kwargs):
    values = {key: required(args, key) for key in ("start_x", "start_y", "end_x", "end_y")}
    if any(isinstance(value, bool) or not isinstance(value, int) or value < 0 for value in values.values()):
        raise ToolInputError("Drag coordinates must be non-negative integers")
    duration = args.get("duration", 500)
    if isinstance(duration, bool) or not isinstance(duration, int):
        raise ToolInputError("duration must be an integer in milliseconds")
    values["duration_ms"] = max(100, min(3000, duration))
    if values["duration_ms"] != duration:
        logger.warning("component=tools operation=android_drag duration clamped to supported range")
    hold = args.get("hold_ms", 0)
    if isinstance(hold, bool) or not isinstance(hold, int):
        raise ToolInputError("hold_ms must be an integer in milliseconds")
    values["hold_ms"] = max(0, min(3000, hold))
    if values["hold_ms"] != hold:
        logger.warning("component=tools operation=android_drag hold_ms clamped to supported range")
    return call("POST", "/drag", args, values)


@tool_handler
def android_scroll(args: dict, **kwargs):
    values = {"direction": required(args, "direction")}
    if args.get("node_id"):
        values["nodeId"] = args["node_id"]
    return call("POST", "/scroll", args, values)


@tool_handler
def android_press_key(args: dict, **kwargs):
    return call("POST", "/press_key", args, {"key": required(args, "key")})


@tool_handler
def android_wait(args: dict, **kwargs):
    values = {"timeoutMs": args.get("timeout_ms", 5000)}
    if args.get("text"):
        values["text"] = args["text"]
    if args.get("class_name"):
        values["className"] = args["class_name"]
    return call("POST", "/wait", args, values)


HANDLERS = {function.__name__: function for function in (
    android_tap, android_tap_text, android_long_press, android_type, android_swipe,
    android_drag, android_scroll, android_press_key, android_wait,
)}
