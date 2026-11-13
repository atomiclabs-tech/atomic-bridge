"""Pin the public tools inventory to the registered plugin contract."""

import re
from collections import Counter
from pathlib import Path

import pytest

from atomic_bridge.registry import TOOL_ENTRIES

ROOT = Path(__file__).resolve().parents[2]
DOCUMENT = ROOT / "docs/TOOLS.md"


def assert_inventory(text):
    names = Counter(re.findall(r"\bandroid_[a-z0-9_]+\b", text))
    expected = {entry.name for entry in TOOL_ENTRIES}
    assert len(expected) == 42
    assert set(names) == expected, "Public tools page differs from the registry"
    assert all(count == 1 for count in names.values()), "Public tools page repeats a tool"


def test_public_tools_doc_matches_registry():
    text = DOCUMENT.read_text()
    assert_inventory(text)
    lines = dict(re.findall(r"^- `(android_[a-z0-9_]+)` — (.+)$", text, re.MULTILINE))
    assert lines == {entry.name: entry.schema["description"] for entry in TOOL_ENTRIES}


@pytest.mark.parametrize("mutation", ["missing", "extra", "duplicate"])
def test_inventory_rejects_planted_name_changes(mutation):
    lines = [f"- `{entry.name}` — {entry.schema['description']}" for entry in TOOL_ENTRIES]
    if mutation == "missing":
        lines.pop()
    elif mutation == "extra":
        lines.append("- `android_planted_extra` — Planted inventory error.")
    else:
        lines.append(lines[0])
    with pytest.raises(AssertionError):
        assert_inventory("\n".join(lines))
