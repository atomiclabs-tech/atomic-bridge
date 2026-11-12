# Ported from plugin/relay/channels/notifications.py at upstream commit da782a8.
"""Bounded per-device posted and removed events, with no disk or transport access."""

import math
import time
from collections import deque

from atomic_relay.bridge.registry import BridgeError
from atomic_relay.bridge.status import normalize_selector


def matches(entry, *, package=None, text=None, since=None, device=None, posted_only=False):
    if posted_only and entry["type"] != "notification.posted":
        return False
    if device is not None and entry["device_id"] != device:
        return False
    if package is not None and entry["package_name"] != package:
        return False
    timestamp = entry.get("posted_at", entry.get("removed_at"))
    if since is not None and timestamp <= since:
        return False
    return text is None or any(text.casefold() in (entry.get(key) or "").casefold() for key in ("title", "text", "sub_text"))


class NotificationBuffer:
    def __init__(self, *, max_entries=500, clock=time.time):
        self.max_entries, self.clock, self.sequence = max_entries, clock, 0
        self.devices, self.aliases = {}, {}

    def append(self, device_id, kind, payload, *, aliases=()):
        if kind not in {"notification.posted", "notification.removed"}:
            raise ValueError("unsupported notification event")
        required = ("key", "package_name")
        if any(not isinstance(payload.get(key), str) for key in required):
            raise ValueError("notification key and package must be strings")
        texts = ("title", "text", "sub_text") if kind == "notification.posted" else ()
        if any(key not in payload or (payload[key] is not None and not isinstance(payload[key], str)) for key in texts):
            raise ValueError("notification text fields must be strings or null")
        timestamp_key = "posted_at" if kind == "notification.posted" else "removed_at"
        timestamp = payload.get(timestamp_key)
        if isinstance(timestamp, bool) or not isinstance(timestamp, (int, float)) or not math.isfinite(timestamp):
            raise ValueError("notification event timestamp is invalid")
        if kind == "notification.posted" and not isinstance(payload.get("ongoing"), bool):
            raise ValueError("notification ongoing flag must be boolean")
        optional = ("ongoing", "category") if kind == "notification.posted" else ("reason",)
        entry = {key: payload[key] for key in (*required, *texts, timestamp_key, *optional) if key in payload}
        events = self.devices.setdefault(device_id, deque(maxlen=self.max_entries))
        if any(row["type"] == kind and row["key"] == entry["key"] and row[timestamp_key] == timestamp for row in events):
            return None
        self.sequence += 1
        entry.update(type=kind, device_id=device_id, received_at=self.clock() * 1000, sequence=self.sequence)
        events.append(entry)
        self.aliases[device_id] = {device_id, normalize_selector(device_id), *aliases}
        return entry

    def recent(self, *, limit=20, **filters):
        entries = [row for events in self.devices.values() for row in events if matches(row, **filters)]
        entries.sort(key=lambda row: (row.get("posted_at", row.get("removed_at")), row["sequence"]), reverse=True)
        return [dict(row) for row in entries[:limit]]

    def first_posted(self, **filters):
        entries = [row for events in self.devices.values() for row in events if matches(row, posted_only=True, **filters)]
        return dict(min(entries, key=lambda row: row["sequence"])) if entries else None

    def select_device(self, selector):
        raw, normalized = selector.strip(), normalize_selector(selector)
        found = [identity for identity, aliases in self.aliases.items() if raw in aliases or normalized in aliases]
        if not found:
            raise BridgeError("Unknown notification device selector", 404)
        if len(found) > 1:
            raise BridgeError("Ambiguous notification device selector", 409)
        return found[0]
