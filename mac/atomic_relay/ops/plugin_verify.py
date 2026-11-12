"""Validate configured state and source-backed, isolated Doctor evidence."""

import json
import re
from pathlib import Path

from atomic_relay.ops.manifest import Manifest

DOCTOR_OK = "OK: runtime discovery, manifest parsing, import, and registration passed"


def check_configured(output: str, selected: Manifest, other: str):
    """The list JSON contract reports configuration only, never active loading."""
    rows = json.loads(output)
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise ValueError("plugins list returned an invalid JSON array")
    for name, expected in ((selected.name, "enabled"), (other, "disabled")):
        matches = [row for row in rows if row.get("name") == name]
        if len(matches) != 1 or matches[0].get("status") != expected:
            raise ValueError(f"configured plugin {name} must be uniquely {expected}")
        if matches[0].get("removed"):
            raise ValueError(f"configured plugin {name} is marked removed")
        if name == selected.name and matches[0].get("version") != selected.version:
            raise ValueError("configured selected plugin version differs from its installed manifest")


def check_doctor(output: str, directory: Path, manifest: Manifest):
    """Clean Doctor name comparisons plus exact counts prove isolated inventory.

Source: hermes_cli/plugin_dev.py DoctorReport.format_text and doctor_plugin.
Doctor warns for every missing/extra declared tool or hook, even with OK.
"""
    # Rich may wrap long lines; joining continuations preserves its text contract.
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    text = " ".join(lines)
    if any(re.search(r"\b(?:WARN|ERROR):", line) for line in lines):
        raise ValueError("Plugin Doctor reported a warning or error; isolated registration refused")
    expected_path = re.escape(f"Plugin Doctor: {directory.resolve()}") + r"\s+manifest:"
    identity = re.escape(f"manifest: {manifest.name} {manifest.version}") + r" \(([a-z-]+)\)"
    found = re.search(identity, text)
    if not re.search(expected_path, text) or found is None or (manifest.kind and found[1] != manifest.kind):
        raise ValueError("Plugin Doctor identity differs from the installed selected manifest")
    if text.count(DOCTOR_OK) != 1:
        raise ValueError("Plugin Doctor did not report exactly one successful isolated registration")
    counts = re.findall(r"registrations: (\d+) tool\(s\), (\d+) hook\(s\)", text)
    if counts != [(str(len(manifest.tools)), str(len(manifest.hooks)))]:
        raise ValueError("Plugin Doctor registration count differs from the exact installed manifest")
    if "provider(s):" in text:
        raise ValueError("unexpected provider registration for the selected tool plugin")
