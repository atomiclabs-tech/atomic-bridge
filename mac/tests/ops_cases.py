"""Isolated operator fixtures. No real service commands or home changes."""

import importlib.util
import json
import os
from pathlib import Path
import re
import shutil

from atomic_relay.ops.layout import Layout
from atomic_relay.ops.manifest import read_manifest

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).parent / "fixtures/cutover"
# Keep generic list/Doctor variants available when private cutover fixtures are omitted.
PLUGIN_LISTINGS = {
    "a": ('[\n'
          '  {"name": "atomic-bridge", "status": "enabled", "version": "2.1.0", "description": "Control the dedicated phone through Atomic.", "source": "user", "removed": ""},\n'
          '  {"name": "hermes-relay", "status": "disabled", "version": "1.13.1", "description": "Prior installed relay", "source": "user", "removed": ""}\n'
          ']\n'),
    "b": ('[{"source":"user","removed":"","description":"Prior installed relay","version":"1.13.1","status":"disabled","name":"hermes-relay"},'
          '{"name":"unrelated-example","status":"enabled","version":"1.0.0","description":"Harmless unrelated plugin","source":"user","removed":""},'
          '{"version":"2.1.0","status":"enabled","name":"atomic-bridge","source":"user","description":"Control the dedicated phone through Atomic.","removed":""}]\n'),
}
DOCTOR_OUTPUTS = {
    "a": ('Plugin Doctor: @PLUGIN_DIR@\n'
          '  manifest: atomic-bridge 2.1.0 (standalone)\n'
          '  OK: runtime discovery, manifest parsing, import, and registration passed\n'
          '  registrations: 42 tool(s), 0 hook(s)\n'),
    "b": ('\nPlugin Doctor: @PLUGIN_DIR@\n'
          '    manifest: atomic-bridge 2.1.0 (standalone)\n'
          '    OK: runtime discovery, manifest parsing, import, and\n'
          '    registration passed\n'
          '    registrations: 42 tool(s), 0 hook(s)\n'),
}


def tool_module(name):
    spec = importlib.util.spec_from_file_location(f"isolated_{name}", ROOT / "tools" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def installed(tmp_path):
    layout = Layout(tmp_path / "operator home", 501)
    layout.service.mkdir(parents=True)
    (layout.service / "atomic_relay").mkdir()
    (layout.service / "atomic_relay/__main__.py").write_text("# isolated fixture\n")
    python = layout.service / ".venv/bin/python"
    python.parent.mkdir(parents=True)
    python.write_text("# never executed\n")
    for source, target in ((ROOT / "mac/pyproject.toml", layout.service / "pyproject.toml"),
                           (ROOT / "mac/.env.example", layout.service / "relay.env")):
        shutil.copyfile(source, target)
    shutil.copytree(ROOT / "mac/atomic_bridge", layout.plugin("atomic-bridge"),
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    old = layout.plugin("hermes-relay")
    old.mkdir()
    # Small manifest intentionally differs from Atomic: counts must come from the selected install.
    (old / "plugin.yaml").write_text(
        "name: hermes-relay\nversion: 1.13.1\nprovides_tools:\n  - android_ping\n"
        "  - android_tap\nprovides_hooks:\n  - on_session_start\n")
    (old / "__init__.py").write_text("# never imported\n")
    for name in ("com.atomic.hermes-relay", "com.atomic.bridge-relay"):
        plist = layout.plist(name)
        plist.parent.mkdir(parents=True, exist_ok=True)
        plist.write_text("isolated plist fixture\n")
    return layout


def fixture_outputs(layout, variant):
    listing = PLUGIN_LISTINGS[variant]
    doctor = DOCTOR_OUTPUTS[variant]
    version = read_manifest(layout.plugin("atomic-bridge")).version
    listing = re.sub(
        r'\{[^{}]*"name"\s*:\s*"atomic-bridge"[^{}]*\}',
        lambda row: re.sub(r'("version"\s*:\s*")[^"]+(")',
                           lambda field: field[1] + version + field[2], row[0]), listing,
    )
    doctor = re.sub(r'(manifest: atomic-bridge )\S+', lambda match: match[1] + version, doctor)
    return listing, doctor.replace("@PLUGIN_DIR@", str(layout.plugin("atomic-bridge").resolve()))


class FakeRunner:
    def __init__(self, layout, *, rollback=False, variant="a"):
        self.layout, self.rollback, self.variant = layout, rollback, variant
        self.calls, self.requirements = [], []
        self.failure = None
        self.override = {}

    def require(self, names):
        self.requirements.append(names)

    def run(self, argv, *, timeout=30, missing_service=False):
        self.calls.append((tuple(argv), timeout, missing_service))
        if self.failure is not None and self.failure.argv == tuple(argv):
            raise self.failure
        if tuple(argv) in self.override:
            return self.override[tuple(argv)]
        if argv[:2] == ("launchctl", "print"):
            current = "com.atomic.bridge-relay" if self.rollback else "com.atomic.hermes-relay"
            return "isolated loaded service\n" if argv[-1].endswith("/" + current) else None
        listing, doctor = fixture_outputs(self.layout, self.variant)
        if argv[:3] == ("hermes", "plugins", "list"):
            if self.rollback:
                rows = json.loads(listing)
                for row in rows:
                    if row["name"] in {"atomic-bridge", "hermes-relay"}:
                        row["status"] = "enabled" if row["name"] == "hermes-relay" else "disabled"
                return json.dumps(rows)
            return listing
        if argv[:3] == ("hermes", "plugins", "doctor"):
            if self.rollback:
                manifest = read_manifest(self.layout.plugin("hermes-relay"))
                return (f"Plugin Doctor: {self.layout.plugin('hermes-relay').resolve()}\n"
                        f"  manifest: hermes-relay {manifest.version} (standalone)\n"
                        "  OK: runtime discovery, manifest parsing, import, and registration passed\n"
                        f"  registrations: {len(manifest.tools)} tool(s), {len(manifest.hooks)} hook(s)\n")
            return doctor
        return "isolated command completed\n"


class FakeProbe:
    def __init__(self, versions):
        self.versions = list(versions)
        self.calls = []
        self.connected = True

    def get(self, path, *, timeout=5):
        self.calls.append((path, timeout))
        if path == "/health":
            version = self.versions.pop(0) if len(self.versions) > 1 else self.versions[0]
            return {"status": "ok", "version": version}
        return {"devices": [{"connected": self.connected, "phone_connected": self.connected,
                             "device_form_factor": "phone", "device": {"application_id": "tech.atomiclabs.link"}}]}


class FakeClock:
    def __init__(self):
        self.now, self.sleeps = 0.0, []

    def clock(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


def isolated_python_env(tmp_path, home):
    """Intercept shell subprocess home resolution and reject live-home filesystem I/O."""
    guard = tmp_path / "python-guard"
    guard.mkdir()
    source = '''import builtins, io, os, sys, shutil
from pathlib import Path
Path.home = classmethod(lambda cls: Path(INJECTED_HOME))
shutil.which = lambda name: "/test-only/fake-" + name
def guarded(function):
    def call(path, *args, **kwargs):
        if isinstance(path, (str, bytes, os.PathLike)):
            text = os.fsdecode(path)
            allowed = (REPO_ROOT, sys.base_prefix, TEMP_ROOT)
            ancestors = {str(parent) for root in allowed for parent in Path(root).parents}
            if function.__name__ in {"stat", "lstat", "readlink"} and text in ancestors:
                return function(path, *args, **kwargs)
            if (text == OWNER_HOME or text.startswith(OWNER_HOME + "/")) and not any(
                    text == root or text.startswith(root + "/") for root in allowed):
                raise AssertionError("live home filesystem access rejected")
        return function(path, *args, **kwargs)
    return call
for module, names in ((builtins, ("open",)), (io, ("open",)),
                      (os, ("open", "stat", "lstat", "listdir", "scandir", "readlink"))):
    for name in names:
        setattr(module, name, guarded(getattr(module, name)))
'''
    header = (f"INJECTED_HOME={str(home)!r}\nOWNER_HOME={str(Path(*ROOT.parts[:3]))!r}\n"
              f"REPO_ROOT={str(ROOT)!r}\nTEMP_ROOT={str(tmp_path)!r}\n")
    (guard / "sitecustomize.py").write_text(header + source)
    return {**os.environ, "PYTHONPATH": str(guard)}
