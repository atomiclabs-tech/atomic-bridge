"""Read the installed manifests' simple identity and registration declarations.

The supported YAML subset is deliberately restricted to the declarations used
by both shipped plugins. Unsupported forms fail rather than guessing YAML.
"""

import json
import re
import shlex
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Manifest:
    name: str
    version: str
    tools: tuple[str, ...]
    hooks: tuple[str, ...]
    kind: str | None


def _scalar(raw):
    parts = shlex.split(raw, comments=True)
    if len(parts) != 1 or not re.fullmatch(r"[A-Za-z0-9_.-]+", parts[0]):
        raise ValueError("unsupported manifest declaration")
    return parts[0]


def _yaml_declarations(text):
    values, section = {}, None
    wanted = {"name", "version", "kind", "provides_tools", "provides_hooks"}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line[0].isspace():
            key, separator, raw = line.partition(":")
            section = None
            if not separator or key not in wanted:
                continue
            if key in values:
                raise ValueError("duplicate manifest declaration")
            if key.startswith("provides_"):
                raw = raw.partition("#")[0].strip()
                if raw not in {"", "[]"}:
                    raise ValueError("unsupported manifest list")
                values[key], section = [], key
            else:
                values[key] = _scalar(raw)
        elif section:
            match = re.fullmatch(r"\s+-\s+(.+)", line)
            if match is None:
                raise ValueError("unsupported manifest list entry")
            values[section].append(_scalar(match[1]))
    return values


def read_manifest(directory: Path) -> Manifest:
    candidates = [directory / name for name in ("plugin.yaml", "plugin.yml", "plugin.json")
                  if (directory / name).is_file()]
    if len(candidates) != 1:
        raise ValueError("expected exactly one installed plugin manifest")
    path = candidates[0]
    text = path.read_text(encoding="utf-8-sig")
    values = json.loads(text) if path.suffix == ".json" else _yaml_declarations(text)
    if not isinstance(values, dict):
        raise ValueError("manifest must be an object")
    for key in ("name", "version"):
        if not isinstance(values.get(key), str) or not values[key]:
            raise ValueError(f"manifest {key} is missing")
    tools = values.get("provides_tools")
    hooks = values.get("provides_hooks", [])
    for names in (tools, hooks):
        if (not isinstance(names, list) or any(not isinstance(name, str) or not name for name in names)
                or len(names) != len(set(names))):
            raise ValueError("manifest registration declarations must be unique string lists")
    if not tools:
        raise ValueError("selected plugin declares no tools")
    return Manifest(values["name"], values["version"], tuple(tools), tuple(hooks), values.get("kind"))
