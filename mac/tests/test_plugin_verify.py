# no_parallel: creates an isolated operator home and copied plugin files.
import json

import pytest

from atomic_relay.ops.manifest import read_manifest
from atomic_relay.ops.plugin_verify import check_configured, check_doctor
from ops_cases import fixture_outputs, installed


@pytest.mark.parametrize("variant", ["a", "b"])
def test_source_backed_list_and_doctor_variations(tmp_path, variant):
    layout = installed(tmp_path)
    directory = layout.plugin("atomic-bridge")
    manifest = read_manifest(directory)
    listing, doctor = fixture_outputs(layout, variant)
    check_configured(listing, manifest, "hermes-relay")
    check_doctor(doctor, directory, manifest)


@pytest.mark.parametrize("change", ["missing", "duplicate", "disabled", "other-enabled", "version", "removed"])
def test_refuses_bad_configured_state(tmp_path, change):
    layout = installed(tmp_path)
    listing, _ = fixture_outputs(layout, "a")
    rows = json.loads(listing)
    if change == "missing":
        rows.pop()
    elif change == "duplicate":
        rows.append(dict(rows[0]))
    elif change == "disabled":
        rows[0]["status"] = "disabled"
    elif change == "other-enabled":
        rows[1]["status"] = "enabled"
    elif change == "version":
        rows[0]["version"] = "stale"
    else:
        rows[0]["removed"] = "removed by catalog"
    with pytest.raises(ValueError):
        check_configured(json.dumps(rows), read_manifest(layout.plugin("atomic-bridge")), "hermes-relay")


@pytest.mark.parametrize("warning", [
    "manifest declares tool 'android_ping' but registration did not add it",
    "registration adds tool 'unexpected' not listed in provides_tools",
    "manifest declares hook 'on_session_start' but registration did not add it",
    "unrecognized runtime warning",
])
def test_ok_with_warning_cannot_prove_registration(tmp_path, warning):
    layout = installed(tmp_path)
    _, doctor = fixture_outputs(layout, "a")
    doctor = doctor.replace("  OK:", f"  WARN: {warning}\n  OK:")
    with pytest.raises(ValueError, match="warning or error"):
        check_doctor(doctor, layout.plugin("atomic-bridge"), read_manifest(layout.plugin("atomic-bridge")))


@pytest.mark.parametrize("before,after", [
    ("41 tool(s)", "40 tool(s)"), ("0 hook(s)", "1 hook(s)"),
    ("  OK:", "  ERROR:"), ("atomic-bridge 2.3.0", "atomic-bridge 1.0.0"),
    ("(standalone)", "(model-provider)"), ("registration passed", "registration failed"),
    ("/atomic-bridge\n", "/atomic-bridge-impostor\n"),
])
def test_refuses_counts_identity_and_incomplete_success(tmp_path, before, after):
    layout = installed(tmp_path)
    _, doctor = fixture_outputs(layout, "a")
    assert before in doctor
    with pytest.raises(ValueError):
        check_doctor(doctor.replace(before, after), layout.plugin("atomic-bridge"),
                     read_manifest(layout.plugin("atomic-bridge")))


@pytest.mark.parametrize("output", ["", "[]", "{}", "[null]", "not JSON"])
def test_absent_or_invalid_evidence_never_succeeds(tmp_path, output):
    layout = installed(tmp_path)
    manifest = read_manifest(layout.plugin("atomic-bridge"))
    with pytest.raises(ValueError):
        check_configured(output, manifest, "hermes-relay")
    with pytest.raises(ValueError):
        check_doctor(output, layout.plugin("atomic-bridge"), manifest)
