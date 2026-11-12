# no_parallel: runs git or a subprocess for pinned or archived source proof.
import copy
import importlib
import json
import subprocess
import sys
from pathlib import Path

import pytest

import atomic_bridge
from atomic_bridge import registry
from atomic_bridge.checks import check_fn
from baseline_sources import require_source, source_paths
from fixtures.extract_upstream_schemas import extract_schemas, fixture_bytes
from recording_ctx import RecordingContext

FIXTURE = Path(__file__).parent / "fixtures" / "android_tool_schemas_1.13.1.json"
REPO = Path(__file__).resolve().parents[2]
NEW_NAMES = ["android_notifications_recent", "android_wait_notification", "android_list_files",
             "android_pull_file", "android_push_file", "android_call_audio_probe"]


def _handler(args: dict, **kwargs):
    return json.dumps({"success": True})


def _source():
    return require_source(source_paths(REPO)["pristine"] / "android_tool.py", repo=REPO)


def test_register_uses_only_table_and_documented_context(monkeypatch):
    schema = {"name": "android_ping", "parameters": {"type": "object", "properties": {}, "required": []}}
    setup = {"name": "android_setup", "parameters": {"type": "object", "properties": {}, "required": []}}
    entries = (registry.ToolEntry("android_ping", schema, _handler), registry.ToolEntry("android_setup", setup, _handler))
    monkeypatch.setattr(registry, "TOOL_ENTRIES", entries)
    ctx = RecordingContext()
    atomic_bridge.register(ctx)
    assert [tool["name"] for tool in ctx.tools] == [entry.name for entry in entries]
    for actual, expected in zip(ctx.tools, entries, strict=True):
        assert actual["toolset"] == "android"
        assert actual["schema"] == expected.schema
        assert isinstance(actual["schema"]["parameters"], dict)
        assert actual["handler"] is expected.handler
        assert isinstance(actual["handler"]({}, session_id="synthetic-id"), str)
        assert actual["check_fn"] is (None if expected.name == "android_setup" else check_fn)


def test_shell_has_no_placeholder_handlers_or_import_registration():
    assert len(registry.TOOL_ENTRIES) == 41
    assert all(callable(entry.handler) for entry in registry.TOOL_ENTRIES)
    assert callable(importlib.reload(atomic_bridge).register)
    ctx = RecordingContext()
    assert ctx.tools == []
    atomic_bridge.register(ctx)
    assert len(ctx.tools) == 41
    assert [tool["name"] for tool in ctx.tools] == [entry.name for entry in registry.TOOL_ENTRIES]


def test_fixture_has_35_original_schemas_and_manifest_names():
    schemas = json.loads(FIXTURE.read_text(encoding="utf-8"))
    assert len(schemas) == 35
    manifest = (REPO / "mac" / "atomic_bridge" / "plugin.yaml").read_text(encoding="utf-8")
    assert [line.strip()[2:] for line in manifest.splitlines() if line.startswith("  - ")] == list(schemas) + NEW_NAMES
    for forbidden in ("python_dependencies:", "requires_env:", "hooks:", "capabilities:"):
        assert forbidden not in manifest
    for name, schema in schemas.items():
        assert schema["name"] == name
        assert isinstance(schema["parameters"], dict)
        assert ("device" in schema["parameters"]["properties"]) is (name != "android_setup")
    assert "Hermes Relay" in schemas["android_return_to_hermes"]["description"]


def test_extractor_regenerates_fixture_byte_identically(tmp_path):
    source = _source()
    upstream = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=source.parents[2],
        check=True, timeout=5, capture_output=True, text=True,
    ).stdout.strip()
    assert upstream.startswith("da782a8")
    assert fixture_bytes(source.read_text(encoding="utf-8")) == FIXTURE.read_bytes()
    output = tmp_path / "regenerated.json"
    subprocess.run([
        str(REPO / "tools" / "py.sh"), str(FIXTURE.with_name("extract_upstream_schemas.py")),
        str(source), str(output),
    ], check=True, timeout=5, capture_output=True, text=True)
    assert output.read_bytes() == FIXTURE.read_bytes()


def test_extractor_does_not_execute_upstream_module():
    original = _source().read_text(encoding="utf-8")
    assert extract_schemas(original + "\nraise RuntimeError('must not execute')\n") == json.loads(FIXTURE.read_text())


@pytest.mark.parametrize("source", ["_SCHEMAS = {}", "_SCHEMAS = dangerous()", "_SCHEMAS = missing"])
def test_extractor_rejects_incomplete_or_nonliteral_sources(source):
    with pytest.raises((ValueError, TypeError)):
        extract_schemas(source)


def _without_description_text(value):
    if isinstance(value, dict):
        return {
            key: _without_description_text(child)
            for key, child in value.items() if not (key == "description" and isinstance(child, str))
        }
    if isinstance(value, list):
        return [_without_description_text(child) for child in value]
    return value


def test_v27_comparison_ignores_only_description_strings_recursively():
    original = json.loads(FIXTURE.read_text(encoding="utf-8"))
    neutral = copy.deepcopy(original)
    neutral["android_return_to_hermes"]["description"] = "Return to Atomic."
    neutral["android_ping"]["parameters"]["properties"]["device"]["description"] = "Choose the phone."
    assert _without_description_text(neutral) == _without_description_text(original)
    neutral["android_read_screen"]["parameters"]["properties"]["include_bounds"]["default"] = True
    assert _without_description_text(neutral) != _without_description_text(original)


@pytest.mark.parametrize("variant", ["a", "b"])
def test_operator_fixtures_work_without_private_cutover_files(tmp_path, monkeypatch, variant):
    import ops_cases

    layout = ops_cases.installed(tmp_path)
    monkeypatch.setattr(ops_cases, "FIXTURES", tmp_path / "unavailable-cutover")
    listing, doctor = ops_cases.fixture_outputs(layout, variant)
    rows = json.loads(listing)
    assert any(row["name"] == "atomic-bridge" for row in rows)
    assert "41 tool(s), 0 hook(s)" in doctor
    assert "@PLUGIN_DIR@" not in doctor


@pytest.mark.parametrize("git_marker", [False, True], ids=["export", "public-checkout"])
def test_missing_public_baseline_skips_without_requiring_git(tmp_path, monkeypatch, git_marker):
    import baseline_sources

    repo = tmp_path / "public"
    (repo / "mac/tests").mkdir(parents=True)
    if git_marker:
        (repo / ".git").mkdir()
    monkeypatch.setattr(baseline_sources, "__file__", str(repo / "mac/tests/baseline_sources.py"))

    def refuse_git(*args, **kwargs):
        raise AssertionError("public provenance lookup must not require git")

    monkeypatch.setattr(baseline_sources.subprocess, "run", refuse_git)
    with pytest.raises(pytest.skip.Exception, match="private provenance"):
        baseline_sources.availability("archived")


def test_missing_private_baseline_is_an_error(tmp_path, monkeypatch):
    import baseline_sources

    repo = tmp_path / "private"
    (repo / "mac/atomic_export").mkdir(parents=True)
    monkeypatch.setattr(baseline_sources, "__file__", str(repo / "mac/tests/baseline_sources.py"))
    monkeypatch.setattr(baseline_sources.subprocess, "run", lambda *args, **kwargs:
                        subprocess.CompletedProcess(args[0], 0, ".git\n"))
    with pytest.raises(FileNotFoundError):
        baseline_sources.availability("archived")


def test_missing_public_upstream_skips_only_the_provenance_lookup(tmp_path, monkeypatch):
    repo = tmp_path / "public"
    (repo / "mac/tests").mkdir(parents=True)
    (repo / ".git").mkdir()
    monkeypatch.setattr(sys.modules[__name__], "REPO", repo)
    with pytest.raises(pytest.skip.Exception, match="private provenance"):
        _source()


def test_missing_private_upstream_is_an_error(tmp_path, monkeypatch):
    repo = tmp_path / "private"
    (repo / "mac/atomic_export").mkdir(parents=True)
    monkeypatch.setattr(sys.modules[__name__], "REPO", repo)
    monkeypatch.setattr(subprocess, "run", lambda *args, **kwargs:
                        subprocess.CompletedProcess(args[0], 0, ".git\n"))
    with pytest.raises(FileNotFoundError):
        _source()
