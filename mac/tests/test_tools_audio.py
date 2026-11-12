# no_parallel: opens a loopback test server on port 0.
import json
import logging

import pytest

from atomic_bridge import tools_audio
from atomic_bridge.schemas_new import SCHEMAS
from new_tool_cases import NewToolRelay, TracedClient, install_client


@pytest.mark.parametrize("args,expected", [
    ({}, {"duration_ms": 4000, "speaker": "both", "inject": True}),
    ({"duration_ms": 0, "speaker": "off", "inject": False}, {"duration_ms": 1000, "speaker": "off", "inject": False}),
    ({"duration_ms": 99999, "speaker": "on"}, {"duration_ms": 10000, "speaker": "on", "inject": True}),
])
def test_probe_exact_wire_clamps_and_long_budget_preserve_phone_report(monkeypatch, caplog, args, expected):
    with NewToolRelay() as relay:
        client = TracedClient(relay)
        install_client(monkeypatch, client)
        with caplog.at_level(logging.WARNING, logger="atomic_bridge"):
            raw = tools_audio.android_call_audio_probe({**args, "device": "synthetic"})
        assert isinstance(raw, str)
        result = json.loads(raw)
        assert result["capture"][0]["initialized"] is False and result["injection"]["played"] is False
        assert client.calls == [("POST", "/call_audio_probe", {**expected, "device": "synthetic"}, 120)]
        assert relay.requests[0]["body"] == {**expected, "device": "synthetic"}
        assert ("clamped" in caplog.text) is bool(args)


@pytest.mark.parametrize("args", [{"speaker": "invalid"}, {"duration_ms": True}, {"inject": "yes"}])
def test_bad_probe_inputs_do_not_dispatch(monkeypatch, args):
    monkeypatch.setattr(tools_audio, "RelayClient", lambda: pytest.fail("Invalid probe reached transport"))
    assert "error" in json.loads(tools_audio.android_call_audio_probe(args))


def test_new_schema_inventory_and_probe_contract():
    assert set(SCHEMAS) == {"android_notifications_recent", "android_wait_notification", "android_list_files", "android_pull_file", "android_push_file", "android_call_audio_probe"}
    properties = SCHEMAS["android_call_audio_probe"]["parameters"]["properties"]
    assert {key: item.get("default") for key, item in properties.items() if key != "device"} == {"duration_ms": 4000, "speaker": "both", "inject": True}
    assert properties["speaker"]["enum"] == ["on", "off", "both"]
    assert all("device" in schema["parameters"]["properties"] for schema in SCHEMAS.values())
