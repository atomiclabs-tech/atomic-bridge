# no_parallel: creates an isolated operator home and copied plugin files.
import json

import pytest

from atomic_relay.ops.manifest import read_manifest
from ops_cases import ROOT, installed


def test_reads_exact_atomic_and_selected_old_declarations(tmp_path):
    actual = read_manifest(ROOT / "mac/atomic_bridge")
    assert actual.name == "atomic-bridge" and actual.version == "2.3.0"
    assert len(actual.tools) == 41 and actual.hooks == () and actual.kind == "standalone"
    old = read_manifest(installed(tmp_path).plugin("hermes-relay"))
    assert old.tools == ("android_ping", "android_tap") and old.hooks == ("on_session_start",)


@pytest.mark.parametrize("declarations", [
    "provides_tools: [android_ping]\n", "provides_tools:\n  - android_ping\n  - android_ping\n",
    "provides_tools:\n  nested: invalid\n", "provides_tools: []\n", "",
    "provides_tools:\n  - android_ping\nname: duplicate\n",
])
def test_unsupported_or_ambiguous_yaml_fails(tmp_path, declarations):
    (tmp_path / "plugin.yaml").write_text("name: example\nversion: 1.0.0\n" + declarations)
    with pytest.raises(ValueError):
        read_manifest(tmp_path)


def test_json_manifest_and_ambiguous_files(tmp_path):
    (tmp_path / "plugin.json").write_text(json.dumps({
        "name": "example", "version": "1.0.0", "provides_tools": ["android_ping"], "provides_hooks": [],
    }))
    assert read_manifest(tmp_path).tools == ("android_ping",)
    (tmp_path / "plugin.yml").write_text("name: duplicate\n")
    with pytest.raises(ValueError, match="exactly one"):
        read_manifest(tmp_path)


def test_quoted_values_and_unrelated_nested_declarations(tmp_path):
    (tmp_path / "plugin.yaml").write_text(
        'name: "example" # comment\nversion: "1.0.0"\nrequires_env:\n  - name: ignored\n'
        'provides_tools:\n  - "android_ping" # declaration\n')
    assert read_manifest(tmp_path).tools == ("android_ping",)
