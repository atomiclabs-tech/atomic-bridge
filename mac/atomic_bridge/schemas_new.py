"""Argument contracts for notification, file and call-audio probe tools."""

# forge-allow: no-test schema declarations only; registration tests cover these contracts.
_DEVICE = {"type": "string", "description": "Optional Atomic Bridge device ID or alias."}
SCHEMAS = {
    "android_notifications_recent": {
        "name": "android_notifications_recent",
        "description": "List recent posted and removed notification events from Atomic Bridge, newest first.",
        "parameters": {"type": "object", "properties": {
            "limit": {"type": "integer", "default": 20},
            "package": {"type": "string"},
            "text": {"type": "string", "description": "Case-insensitive match on title, text or sub-text of posted events; removal events carry no text and are excluded when this is set."},
            "since_minutes": {"type": "number", "description": "Return events newer than this many minutes ago, by their posted or removed time."},
            "device": _DEVICE,
        }, "required": []},
    },
    "android_wait_notification": {
        "name": "android_wait_notification",
        "description": "Wait for the first matching notification from Atomic Bridge, or report no match. Removal events never match.",
        "parameters": {"type": "object", "properties": {
            "package": {"type": "string"}, "text": {"type": "string"},
            "timeout_s": {"type": "integer", "default": 60, "description": "Wait duration, clamped to 1 through 120 seconds."},
            "device": _DEVICE,
        }, "required": []},
    },
    "android_list_files": {
        "name": "android_list_files",
        "description": "List an accessible folder on Atomic Bridge, including file sizes and modification times.",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string"}, "limit": {"type": "integer", "default": 200},
            "device": _DEVICE,
        }, "required": []},
    },
    "android_pull_file": {
        "name": "android_pull_file",
        "description": "Pull a file up to 256 MiB from Atomic Bridge, verify its size and SHA-256, and save it atomically.",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string", "description": "Path of the file on Atomic Bridge."},
            "dest": {"type": "string", "description": "Local file or directory. Omit to use the configured phone-files directory."},
            "device": _DEVICE,
        }, "required": ["path"]},
    },
    "android_push_file": {
        "name": "android_push_file",
        "description": "Push a local file up to 256 MiB to Atomic Bridge with size and SHA-256 verification.",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string", "description": "Local source file."},
            "dir": {"type": "string", "description": "Optional destination folder on Atomic Bridge."},
            "name": {"type": "string", "description": "Optional destination filename on Atomic Bridge."},
            "overwrite": {"type": "boolean", "default": False}, "device": _DEVICE,
        }, "required": ["path"]},
    },
    "android_call_audio_probe": {
        "name": "android_call_audio_probe",
        "description": "Measure call-audio capture and playback on Atomic Bridge and return the phone app report.",
        "parameters": {"type": "object", "properties": {
            "duration_ms": {"type": "integer", "default": 4000, "description": "Capture duration, clamped to 1000 through 10000 milliseconds."},
            "speaker": {"type": "string", "enum": ["on", "off", "both"], "default": "both"},
            "inject": {"type": "boolean", "default": True}, "device": _DEVICE,
        }, "required": []},
    },
    "android_unlock": {
        "name": "android_unlock",
        "description": "Unlock the Atomic Bridge phone's PIN lock screen using the PIN file configured on the relay Mac; the PIN is never passed as an argument. One attempt per call.",
        "parameters": {"type": "object", "properties": {"device": _DEVICE}, "required": []},
    },
}
