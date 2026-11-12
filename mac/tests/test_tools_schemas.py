# no_parallel: opens a loopback test server on port 0.
import copy
import hashlib
import json
from pathlib import Path
import re

import pytest

import atomic_bridge
from atomic_bridge.checks import check_fn
from atomic_bridge.client import RelayClient
from fake_relay import admin_environment, FakeRelay, FakeResponse
from recording_ctx import RecordingContext
from tool_cases import CASES, PNG, install_client

FIXTURE = Path(__file__).parent / "fixtures" / "android_tool_schemas_1.13.1.json"


def _without_description_text(value):
    if isinstance(value, dict):
        return {key: "<description>" if key == "description" and isinstance(child, str)
                else _without_description_text(child) for key, child in value.items()}
    if isinstance(value, list):
        return [_without_description_text(child) for child in value]
    return value


def _descriptions(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "description":
                yield child
            else:
                yield from _descriptions(child)
    elif isinstance(value, list):
        for child in value:
            yield from _descriptions(child)


def test_all_registered_schemas_match_fixture_plus_drag_hold_except_description_text():
    ctx = RecordingContext()
    atomic_bridge.register(ctx)
    registered = {tool["name"]: tool["schema"] for tool in ctx.tools}
    expected = json.loads(FIXTURE.read_text())
    expected["android_drag"]["parameters"]["properties"]["hold_ms"] = {
        "type": "integer", "default": 0,
        "description": "Hold at the start point before moving, 0 to 3000 ms (default 0). Use 500 or more to pick up a launcher icon.",
    }
    actual = {name: registered[name] for name in expected}
    assert len(actual) == 35
    assert len(registered) == len(ctx.tools) == 41
    assert set(registered) - set(expected) == {
        "android_notifications_recent", "android_wait_notification", "android_list_files",
        "android_pull_file", "android_push_file", "android_call_audio_probe",
    }
    assert hashlib.sha256(FIXTURE.read_bytes()).hexdigest() == "c32eed6286737fab83165e08d50643e6015d04d7799726fe8f102719be2c8039"
    assert _without_description_text(actual) == _without_description_text(expected)
    for description in _descriptions(registered):
        assert isinstance(description, str)
        assert not any(word in description.lower() for word in ("hermes", "agent", "confirmation modal", "blocklist"))
    assert set(actual) == {case[0] for case in CASES}
    for tool in ctx.tools:
        assert tool["toolset"] == "android"
        assert tool["check_fn"] is (None if tool["name"] == "android_setup" else check_fn)
        assert tool["handler"].__module__.startswith("atomic_bridge.tools_")


def test_phone_app_descriptions_use_the_full_name():
    ctx = RecordingContext()
    atomic_bridge.register(ctx)
    descriptions = list(_descriptions({tool["name"]: tool["schema"] for tool in ctx.tools}))
    has_full_name = any("Atomic Bridge" in text for text in descriptions)
    has_old_name = any(re.search(r"\bAtomic\b(?! Bridge\b)", text) for text in descriptions)
    assert has_full_name
    assert not has_old_name


def test_comparator_preserves_description_keys_and_every_other_field():
    source = {"description": "original", "parameters": {"default": False, "description": "nested"}}
    changed = copy.deepcopy(source)
    changed["parameters"]["description"] = "Atomic"
    assert _without_description_text(source) == _without_description_text(changed)
    for mutation in ({"description": {}, "parameters": source["parameters"]}, {"parameters": source["parameters"]}, {"description": "original", "parameters": {"default": True, "description": "nested"}}):
        assert _without_description_text(source) != _without_description_text(mutation)


@pytest.mark.parametrize("outcome", ["success", "phone_failure", "http_error", "transport_error"])
@pytest.mark.parametrize("case", CASES, ids=lambda case: case[0])
def test_all_35_handlers_accept_host_kwargs_and_return_supported_results(monkeypatch, case, outcome):
    import base64

    value = {"success": True, "image": base64.b64encode(PNG).decode()}
    status = 200
    if outcome == "phone_failure":
        value = {"success": False, "code": "unsupported_command", "error": "synthetic phone failure"}
    elif outcome == "http_error":
        status, value = 503, {"error": "synthetic unavailable"}
    with FakeRelay(lambda request: FakeResponse(status=status, value=value)) as relay:
        client = RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()})
        if outcome == "transport_error":
            def fail(*args, **kwargs):
                raise OSError("synthetic dependency failure")
            monkeypatch.setattr(client, "request", fail)
        install_client(monkeypatch, client)
        ctx = RecordingContext()
        atomic_bridge.register(ctx)
        handler = next(tool["handler"] for tool in ctx.tools if tool["name"] == case[0])
        result = handler(copy.deepcopy(case[1]), session_id="synthetic", task_id="synthetic")
    if isinstance(result, dict):
        assert case[0] == "android_screenshot" and outcome == "success"
        assert result.get("_multimodal") is True and isinstance(result.get("content"), list)
        assert isinstance(result.get("text_summary"), str)
    else:
        assert isinstance(result, str)
        decoded = json.loads(result)
        assert isinstance(decoded, dict)
        if outcome == "success":
            assert decoded.get("success") is not False and not decoded.get("error")
        else:
            assert decoded.get("success") is False or "error" in decoded


def test_every_product_module_fits_source_limit():
    root = Path(atomic_bridge.__file__).parent
    assert all(len(path.read_text().splitlines()) <= 500 for path in root.glob("*.py"))


@pytest.mark.parametrize("name", [entry.name for entry in atomic_bridge.registry.TOOL_ENTRIES])
def test_attack11_every_tool_reports_fixed_missing_admin_error_without_http(monkeypatch, tmp_path, name):
    from new_tool_cases import install_client as install_new_client

    source = tmp_path / "synthetic.bin"
    source.write_bytes(b"synthetic")
    arguments = {case[0]: copy.deepcopy(case[1]) for case in CASES}
    arguments.update({
        "android_notifications_recent": {}, "android_wait_notification": {}, "android_list_files": {},
        "android_pull_file": {"path": "synthetic"}, "android_push_file": {"path": str(source)},
        "android_call_audio_probe": {},
    })

    class NoNetwork:
        def open(self, *args, **kwargs):
            pytest.fail("Missing credential reached HTTP")

    client = RelayClient(environ=admin_environment(ATOMIC_BRIDGE_ADMIN_SECRET_FILE=str(tmp_path / "missing.secret")), opener=NoNetwork())
    install_client(monkeypatch, client)
    install_new_client(monkeypatch, client)
    handler = next(entry.handler for entry in atomic_bridge.registry.TOOL_ENTRIES if entry.name == name)
    result = json.loads(handler(arguments[name]))
    assert result["error"] == "Admin secret file is missing or empty; run tools/install-mac.sh or set ATOMIC_BRIDGE_ADMIN_SECRET_FILE"
