"""Request the phone app's call-audio measurements without interpreting them."""

import logging

from .client import RelayClient
from .result import json_result
from .tool_support import ToolInputError, selected, tool_handler

logger = logging.getLogger("atomic_bridge")


@tool_handler
def android_call_audio_probe(args: dict, **kwargs):
    duration = args.get("duration_ms", 4000)
    speaker, inject = args.get("speaker", "both"), args.get("inject", True)
    if isinstance(duration, bool) or not isinstance(duration, int):
        raise ToolInputError("duration_ms must be an integer")
    if speaker not in ("on", "off", "both"):
        raise ToolInputError("speaker must be on, off or both")
    if not isinstance(inject, bool):
        raise ToolInputError("inject must be a boolean")
    bounded = max(1000, min(10000, duration))
    if bounded != duration:
        logger.warning("component=tools operation=android_call_audio_probe duration clamped to supported range")
    return json_result(RelayClient().request("POST", "/call_audio_probe", selected(args, {
        "duration_ms": bounded, "speaker": speaker, "inject": inject,
    }), timeout=120).value)


HANDLERS = {"android_call_audio_probe": android_call_audio_probe}
