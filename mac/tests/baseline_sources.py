"""Isolate archived availability and pinned upstream functions without live access."""

import ast
import hashlib
import subprocess
import sys
from pathlib import Path
from types import ModuleType, SimpleNamespace

import pytest

from tool_cases import MARKER


def _private_checkout(repo):
    return any((repo / marker).exists() for marker in ("AGENTS.md", ".apm", "_forge", "mac/atomic_export"))


def source_paths(repo=None):
    repo = Path(repo) if repo is not None else Path(__file__).resolve().parents[2]
    root = repo
    if _private_checkout(repo):
        common = subprocess.run(["git", "rev-parse", "--git-common-dir"], cwd=repo,
                                check=True, timeout=5, capture_output=True, text=True).stdout.strip()
        root = (repo / common).resolve().parent
    return {
        "archived": root / ".apm/archives/atomic-bridge-v2/memory/stage-02/evidence/plugin-agent/installed-check-requirements.txt",
        "pristine": root.parent / "hermes-relay-upstream" / "plugin" / "tools",
    }


def require_source(path, *, repo=None):
    repo = Path(repo) if repo is not None else Path(__file__).resolve().parents[2]
    if not path.is_file():
        if not _private_checkout(repo):
            pytest.skip("private provenance source unavailable in public distribution")
        raise FileNotFoundError("Required private provenance source is unavailable")
    return path


def _function(path, name, namespace):
    source = path.read_text()
    if path.suffix == ".txt":
        # The archived excerpt ends with the beginning of an unrelated function.
        source = source.split("\ndef _post(", 1)[0]
    tree = ast.parse(source)
    function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == name)
    exec(compile(ast.Module(body=[function], type_ignores=[]), str(path), "exec"), namespace)
    return namespace[name]


def availability(target, *, status=200, value=None):
    calls = []

    def request(method, path, *, timeout):
        calls.append((method, path, timeout))
        result = {"pong": True} if path == "/ping" else value
        return SimpleNamespace(status_code=200 if path == "/ping" else status, json=lambda: result)

    path = source_paths()[target] if target == "archived" else source_paths()[target] / "android_tool.py"
    return _function(require_source(path), "_check_requirements", {"_bridge_request": request}), calls


def pristine_screenshot(monkeypatch):
    source = source_paths()["pristine"]
    resolver_path = require_source(source / "android_screenshot_media.py")
    # Recorded v2 provenance gives the same digest for captured and pinned resolvers.
    assert hashlib.sha256(resolver_path.read_bytes()).hexdigest() == "3a80c169408d2489a18ff559683eb7e0be6d51b3f8c35dfa09ff2f4f96bdfadd"
    tree = ast.parse(resolver_path.read_text())
    # Its only external import is replaced; all decoding and status decisions remain original.
    tree.body = [node for node in tree.body if not (isinstance(node, ast.Import) and any(alias.name == "requests" for alias in node.names))]
    resolver = {"requests": SimpleNamespace(RequestException=OSError)}
    exec(compile(tree, str(resolver_path), "exec"), resolver)
    package = ModuleType("atomic_baseline")
    package.__path__ = []
    module = ModuleType("atomic_baseline.android_screenshot_media")
    module.resolve_screenshot = resolver["resolve_screenshot"]
    monkeypatch.setitem(sys.modules, package.__name__, package)
    monkeypatch.setitem(sys.modules, module.__name__, module)
    namespace = {
        "__package__": package.__name__, "__name__": "atomic_baseline.android_tool",
        "_get": lambda path: {"media": MARKER}, "_timeout": lambda: 2,
        "_bridge_request": lambda *args, **kwargs: SimpleNamespace(status_code=410, close=lambda: None),
    }
    return _function(require_source(source / "android_tool.py"), "android_screenshot", namespace)
