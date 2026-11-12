import json
import logging

import pytest

from atomic_bridge import tool_support
from atomic_bridge.tool_support import ToolFailure, ToolInputError, optional, required, selected, tool_handler


@pytest.mark.parametrize("failure", [ToolInputError("Invalid controlled argument"), ToolFailure({"error": "controlled response", "code": "unsupported_command"}), RuntimeError("synthetic-private-value")])
def test_entry_handler_returns_json_and_logs_safe_stack(failure, caplog):
    @tool_handler
    def handler(args: dict, **kwargs):
        raise failure

    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        result = json.loads(handler({"text": "synthetic-private-text"}))
    assert "error" in result
    assert "synthetic-private-value" not in str(result)
    assert "synthetic-private" not in caplog.text
    assert any(record.exc_info for record in caplog.records)


def test_arguments_are_copied_and_missing_arguments_fail():
    args = {"device": "synthetic", "text": "synthetic", "none": None}
    assert selected(args, {"limit": 2}) == {"device": "synthetic", "limit": 2}
    assert optional(args, ("text", "none")) == {"text": "synthetic"}
    assert required(args, "text") == "synthetic"
    with pytest.raises(ToolInputError):
        required(args, "absent")
    wrapped = tool_handler(lambda values, **kwargs: values.pop("text"))
    assert wrapped(args) == "synthetic"
    assert args["text"] == "synthetic"
    assert "error" in json.loads(wrapped([]))


def test_call_serializes_response_and_does_not_mutate(monkeypatch):
    calls = []

    class Client:
        def request(self, *args):
            calls.append(args)
            return type("Response", (), {"value": {"success": False, "error": "synthetic", "code": "unsupported_command"}})()

    monkeypatch.setattr(tool_support, "RelayClient", Client)
    args = {"device": "synthetic"}
    result = json.loads(tool_support.call("POST", "/tap", args, {"nodeId": "w0:1"}))
    assert result["success"] is False
    assert calls == [("POST", "/tap", {"device": "synthetic", "nodeId": "w0:1"})]
    assert args == {"device": "synthetic"}
