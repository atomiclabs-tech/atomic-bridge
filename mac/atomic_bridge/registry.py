# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Registration entries for the frozen Atomic tool inventory."""

# forge-allow: no-test declarations only; registration behavior is tested separately.
from dataclasses import dataclass
from typing import Callable

from . import schemas_apps, schemas_input, schemas_new, schemas_phone, schemas_screen, schemas_setup
from . import tools_apps, tools_audio, tools_files, tools_input, tools_notifications, tools_phone, tools_screen, tools_setup


@dataclass(frozen=True)
class ToolEntry:
    name: str
    schema: dict
    handler: Callable


_SCHEMAS = {
    **schemas_screen.SCHEMAS, **schemas_input.SCHEMAS, **schemas_apps.SCHEMAS,
    **schemas_phone.SCHEMAS, **schemas_setup.SCHEMAS, **schemas_new.SCHEMAS,
}
_HANDLERS = {
    **tools_screen.HANDLERS, **tools_input.HANDLERS, **tools_apps.HANDLERS,
    **tools_phone.HANDLERS, **tools_setup.HANDLERS, **tools_notifications.HANDLERS,
    **tools_files.HANDLERS, **tools_audio.HANDLERS,
}
_NAMES = (
    "android_ping", "android_read_screen", "android_find_nodes", "android_tap", "android_tap_text",
    "android_long_press", "android_type", "android_swipe", "android_drag", "android_open_app",
    "android_press_key", "android_screenshot", "android_scroll", "android_wait", "android_get_apps",
    "android_current_app", "android_media", "android_describe_node", "android_setup", "android_macro",
    "android_clipboard_read", "android_clipboard_write", "android_screen_hash", "android_diff_screen",
    "android_send_intent", "android_broadcast", "android_events", "android_event_stream",
    "android_location", "android_search_contacts", "android_call", "android_send_sms",
    "android_return_to_hermes", "android_share_media", "android_send_mms",
    "android_notifications_recent", "android_wait_notification", "android_list_files",
    "android_pull_file", "android_push_file", "android_call_audio_probe",
)
TOOL_ENTRIES = tuple(ToolEntry(name, _SCHEMAS[name], _HANDLERS[name]) for name in _NAMES)
