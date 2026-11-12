import pytest

from atomic_bridge.resolve_result import Found, NotFound, PermissionDenied, from_bridge_response


@pytest.mark.parametrize("code,permission", [("code", "permission"), ("error_code", "required_permission")])
def test_permission_classification_accepts_canonical_and_legacy_fields(code, permission):
    source = {code: "permission_denied", permission: "android.permission.CAMERA", "error": "synthetic"}
    assert from_bridge_response(source, found_value=[]) == PermissionDenied("android.permission.CAMERA", "synthetic")
    assert source["error"] == "synthetic"


@pytest.mark.parametrize("value", [False, 0, [], {}, ""])
def test_explicit_empty_results_are_found(value):
    assert from_bridge_response({}, found_value=value) == Found(value)


def test_missing_and_malformed_permission_fields_keep_classification():
    assert from_bridge_response({"error": "synthetic"}) == NotFound("synthetic")
    assert from_bridge_response({"error": 3}) == NotFound()
    assert from_bridge_response({"code": "permission_denied", "permission": False, "error": 7}) == PermissionDenied("")
