import json

from atomic_bridge.result import json_result, multimodal_result, permission_message


def test_permission_failure_changes_only_message():
    original = {
        "success": False, "code": "permission_denied", "permission": "android.permission.CAMERA",
        "error": "old phone app message", "result": {"unchanged": True},
    }
    actual = json.loads(json_result(original))
    assert actual["error"] == (
        "Grant android.permission.CAMERA to the phone app in Settings > Apps > Atomic Bridge > Permissions"
    )
    assert {key: value for key, value in actual.items() if key != "error"} == {
        key: value for key, value in original.items() if key != "error"
    }
    assert original["error"] == "old phone app message"


def test_other_failures_and_success_are_unchanged():
    for value in ({"success": True}, {"success": False, "code": "unsupported_command", "error": "unsupported"}):
        assert json.loads(json_result(value)) == value
    assert permission_message({"code": "permission_denied"}) == {"code": "permission_denied"}


def test_multimodal_envelope_keeps_content_and_summary_without_aliases():
    content = [{"type": "text", "text": "Synthetic image summary"}]
    actual = multimodal_result(content, "Synthetic image summary")
    assert actual == {"_multimodal": True, "content": content, "text_summary": "Synthetic image summary"}
    content[0]["text"] = "Changed caller content"
    assert actual["content"][0]["text"] == "Synthetic image summary"
