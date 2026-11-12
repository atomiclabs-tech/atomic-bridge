# no_parallel: opens a loopback test server on port 0.
import json

import pytest

from atomic_bridge import tools_phone
from atomic_bridge.client import RelayClient
from fake_relay import admin_environment, FakeRelay, FakeResponse
from tool_cases import CASES, assert_wire_case, install_client


@pytest.mark.parametrize("device", [False, True])
@pytest.mark.parametrize("case", [case for case in CASES if case[0] in tools_phone.HANDLERS], ids=lambda case: case[0])
def test_phone_wire_matches_upstream(monkeypatch, case, device):
    assert_wire_case(monkeypatch, case, device)


@pytest.mark.parametrize("case", [case for case in CASES if case[0] in tools_phone.HANDLERS], ids=lambda case: case[0])
def test_permission_result_rewords_only_message(monkeypatch, case):
    value = {"success": False, "code": "permission_denied", "permission": "android.permission.CALL_PHONE", "error": "old app message", "extra": 3}
    with FakeRelay(lambda request: FakeResponse(value=value)) as relay:
        install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()}))
        actual = json.loads(tools_phone.HANDLERS[case[0]](case[1]))
    assert "Atomic" in actual["error"] and "Hermes" not in actual["error"]
    assert {key: item for key, item in actual.items() if key != "error"} == {key: item for key, item in value.items() if key != "error"}


def test_return_command_keeps_unsupported_response(monkeypatch):
    value = {"success": False, "code": "unsupported_command", "error": "Command unsupported"}
    with FakeRelay(lambda request: FakeResponse(value=value)) as relay:
        install_client(monkeypatch, RelayClient(environ={"ANDROID_BRIDGE_URL": relay.url, **admin_environment()}))
        assert json.loads(tools_phone.android_return_to_hermes({})) == value


@pytest.mark.parametrize("name,args", [("android_share_media", {}), ("android_send_mms", {"to": ""}), ("android_send_mms", {"to": "synthetic"})])
def test_empty_share_or_mms_fails_before_phone_call(monkeypatch, name, args):
    class NoCalls:
        def request(self, *args, **kwargs):
            pytest.fail("Empty share reached phone")
    monkeypatch.setattr(tools_phone, "RelayClient", NoCalls)
    assert "error" in json.loads(tools_phone.HANDLERS[name](args))
