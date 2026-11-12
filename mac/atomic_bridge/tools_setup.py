# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Loopback diagnostics and in-process Atomic workflows."""

import json
import logging
import re
import time

from .result import json_result
from .tool_support import RelayClient, ToolInputError, failure_info, required, tool_handler

logger = logging.getLogger("atomic_bridge")


def _redact_pairing_code(value, code):
    if isinstance(value, str):
        return value.replace(code, "[redacted]")
    if isinstance(value, dict):
        return {key: _redact_pairing_code(child, code) for key, child in value.items()}
    if isinstance(value, list):
        return [_redact_pairing_code(child, code) for child in value]
    return value


@tool_handler
def android_setup(args: dict, **kwargs):
    client = RelayClient()
    results = {}
    operations = [("GET", path, None) for path in ("/health", "/bridge/status", "/ping")]
    if args.get("pairing_code") is not None:
        code = args["pairing_code"]
        if isinstance(code, str) and re.fullmatch(r"[A-Z0-9]{6}", code):
            operations.insert(0, ("POST", "/pairing/register", {"code": code}))
        else:
            results["/pairing/register"] = {"status": None, "result": {"error": "Pairing code must contain six A-Z or 0-9 characters"}}
    for method, path, body in operations:
        try:
            response = client.request(method, path, body, timeout=2)
            results[path] = {"status": response.status, "result": response.value}
        except Exception as exc:
            # A failed diagnostic must not prevent reporting the remaining probes.
            logger.exception("component=tools operation=android_setup failed", exc_info=failure_info(exc))
            results[path] = {"status": None, "result": {"error": f"Diagnostic failed: {type(exc).__name__}"}}
    successful = all(
        item["status"] is not None and 200 <= item["status"] < 300
        and not item["result"].get("error") and item["result"].get("success") is not False
        for item in results.values()
    )
    report = {"success": successful, "diagnostics": results}
    if not successful:
        first_error = next(iter(results.values()))["result"].get("error")
        same_error = isinstance(first_error, str) and all(item["result"].get("error") == first_error for item in results.values())
        report["error"] = first_error if same_error else "One or more Atomic diagnostics failed"
    if "bridge_session_token" in args:
        report["note"] = "Supplied session token ignored; Atomic authenticates with its admin credential."
    code = args.get("pairing_code")
    if isinstance(code, str) and code:
        report = _redact_pairing_code(report, code)
    return json_result(report)


def _step_result(raw):
    if isinstance(raw, str):
        parsed = json.loads(raw)
        if not isinstance(parsed, dict):
            raise ToolInputError("Workflow step must return a JSON object")
        return parsed
    if isinstance(raw, dict) and raw.get("_multimodal") is True:
        if not isinstance(raw.get("text_summary"), str):
            raise ToolInputError("Screenshot step must return a text summary")
        return {"summary": raw["text_summary"]}
    raise ToolInputError("Workflow step returned an unsupported result type")


def _run_steps(args, handlers):
    steps = required(args, "steps")
    pace = args.get("pace_ms", 500)
    name = args.get("name", "unnamed")
    results = []
    if not isinstance(steps, list):
        raise ToolInputError("steps must be a list")
    if isinstance(pace, bool) or not isinstance(pace, int) or pace < 0:
        raise ToolInputError("pace_ms must be a non-negative integer")
    for index, step in enumerate(steps):
        error = None
        if not isinstance(step, dict) or not isinstance(step.get("tool"), str) or step["tool"] not in handlers:
            error = "Step must name a registered Atomic tool"
        elif not isinstance(step.get("args", {}), dict):
            error = "Step args must be an object"
        if error:
            return {"success": False, "name": name, "completed": index, "results": results, "error": error}
        arguments = dict(step.get("args", {}))
        if "device" not in arguments and "device" in args and step["tool"] != "android_setup":
            arguments["device"] = args["device"]
        parsed = _step_result(handlers[step["tool"]](arguments))
        results.append({"tool": step["tool"], "result": parsed})
        if parsed.get("success") is False or "error" in parsed or parsed.get("status") == "error":
            return {
                "success": False, "name": name, "completed": index, "results": results,
                "error": parsed.get("error") or parsed.get("message") or "Workflow step failed",
            }
        if index + 1 < len(steps) and pace:
            time.sleep(pace / 1000)
    return {"success": True, "name": name, "completed": len(steps), "results": results}


@tool_handler
def android_macro(args: dict, **kwargs):
    from .registry import TOOL_ENTRIES

    return json_result(_run_steps(args, {entry.name: entry.handler for entry in TOOL_ENTRIES}))


HANDLERS = {"android_setup": android_setup, "android_macro": android_macro}
