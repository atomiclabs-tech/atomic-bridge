# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Tool result formats accepted by the host's documented plugin API."""

import copy
import json


def permission_message(value: dict) -> dict:
    """Reword only a permission failure's human message, preserving its fields."""
    result = copy.deepcopy(value)
    if result.get("code") == "permission_denied" and result.get("permission"):
        result["error"] = (
            f"Grant {result['permission']} to the phone app in "
            "Settings > Apps > Atomic Bridge > Permissions"
        )
    return result


def json_result(value: dict) -> str:
    """Serialize one tool result without changing its wire fields."""
    return json.dumps(permission_message(value), ensure_ascii=False)


def multimodal_result(content: list[dict], text_summary: str) -> dict:
    """Build the supported multimodal envelope with a text-only summary."""
    return {"_multimodal": True, "content": copy.deepcopy(content), "text_summary": text_summary}
