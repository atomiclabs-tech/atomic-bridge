# no_parallel: patches subprocess.run; all vault calls use an injected fake runner.
import ast
import json
import runpy
import secrets
import shlex
import subprocess
from pathlib import Path
from types import SimpleNamespace

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "tools/type_secret_vault.py"


@pytest.fixture
def vault_calls(monkeypatch):
    state = SimpleNamespace(output="", failure=None, calls=[], private=secrets.token_urlsafe(24))

    def run(argv, **kwargs):
        state.calls.append((list(argv), kwargs))
        if state.failure is not None:
            raise state.failure
        return subprocess.CompletedProcess(argv, 0, stdout=state.output, stderr=state.private)

    monkeypatch.setattr(subprocess, "run", run)
    state.module = runpy.run_path(str(SCRIPT))
    return state


@pytest.mark.parametrize("kind", ["exit", "timeout", "os", "value"])
def test_runner_failure_keeps_cause_and_fixed_message(vault_calls, kind):
    state = vault_calls
    failures = {
        "exit": subprocess.CalledProcessError(3, ["synthetic"], output=state.private, stderr=state.private),
        "timeout": subprocess.TimeoutExpired(["synthetic"], 60, output=state.private, stderr=state.private),
        "os": OSError(state.private), "value": ValueError(state.private),
    }
    state.failure = failures[kind]
    with pytest.raises(state.module["SecretError"]) as caught:
        state.module["_run"](["synthetic", "status"], {}, "bw status")
    assert str(caught.value) == "bw status failed"
    assert caught.value.__cause__ is state.failure
    assert bool(state.private not in str(caught.value))
    assert state.calls[0][1] == {"env": {}, "capture_output": True, "text": True, "check": True, "timeout": 60}


def test_session_ownership_quoting_and_caller_precedence(vault_calls):
    state = vault_calls
    supplied = " " + state.private + "\n"
    assert bool(state.module["session"]({"BW_SESSION": supplied, "ATOMIC_BW_SESSION_CMD": "unused"}) == (supplied, False))
    assert state.calls == []
    state.output = supplied
    command = shlex.join(["synthetic-command", "quoted argument"])
    assert bool(state.module["session"]({"ATOMIC_BW_SESSION_CMD": command}) == (state.private, True))
    assert state.calls[0][0] == ["synthetic-command", "quoted argument"]
    assert state.calls[0][1]["timeout"] == 60


@pytest.mark.parametrize("env, expected", [
    ({}, "Set BW_SESSION or ATOMIC_BW_SESSION_CMD"),
    ({"BW_SESSION": ""}, "BW_SESSION is empty"),
    ({"ATOMIC_BW_SESSION_CMD": " "}, "Set BW_SESSION or ATOMIC_BW_SESSION_CMD"),
    ({"ATOMIC_BW_SESSION_CMD": "'"}, "ATOMIC_BW_SESSION_CMD must be a valid command line"),
])
def test_invalid_session_configuration_has_no_child_call(vault_calls, env, expected):
    with pytest.raises(vault_calls.module["SecretError"]) as caught:
        vault_calls.module["session"](env)
    assert expected in str(caught.value)
    assert vault_calls.calls == []


def test_empty_owned_session_is_loud(vault_calls):
    vault_calls.output = " \n"
    with pytest.raises(vault_calls.module["SecretError"], match="empty session"):
        vault_calls.module["session"]({"ATOMIC_BW_SESSION_CMD": "synthetic-command"})


@pytest.mark.parametrize("state", ["locked", "unlocked", "unauthenticated"])
def test_status_projects_only_server_and_state(vault_calls, state):
    vault_calls.output = json.dumps({"serverUrl": "https://vault.example.com", "status": state,
                                    "userEmail": vault_calls.private, "other": vault_calls.private})
    result = vault_calls.module["status"]("synthetic", {})
    assert result == ("https://vault.example.com", state)
    assert bool(vault_calls.private not in repr(result))
    assert vault_calls.calls[0][0] == ["synthetic", "status"]


@pytest.mark.parametrize("value", [None, ""])
def test_status_allows_the_documented_default_server(vault_calls, value):
    vault_calls.output = json.dumps({"status": "unlocked", "serverUrl": value})
    assert vault_calls.module["status"]("synthetic", {}) == (value, "unlocked")


@pytest.mark.parametrize("payload, message", [
    ("{", "invalid JSON"), ("[]", "invalid status"), ("{}", "invalid status"),
    ('{"status": "unknown"}', "invalid status"),
    ('{"status": "unlocked", "serverUrl": 4}', "Run: bw config server <url>"),
])
def test_invalid_status_has_fixed_error(vault_calls, payload, message):
    vault_calls.output = payload
    with pytest.raises(vault_calls.module["SecretError"]) as caught:
        vault_calls.module["status"]("synthetic", {})
    assert message in str(caught.value)


@pytest.mark.parametrize("kind", ["credentials", "query", "fragment", "scheme", "port", "whitespace"])
def test_invalid_server_is_not_echoed(vault_calls, kind):
    private = vault_calls.private
    urls = {"credentials": f"https://{private}@vault", "query": f"https://vault.example.com?token={private}",
            "fragment": f"https://vault.example.com#{private}", "scheme": private,
            "port": f"https://vault.example.com:{private}", "whitespace": f"https://vault.example.com/\n{private}"}
    vault_calls.output = json.dumps({"status": "unlocked", "serverUrl": urls[kind]})
    with pytest.raises(vault_calls.module["SecretError"]) as caught:
        vault_calls.module["status"]("synthetic", {})
    assert str(caught.value) == "Run: bw config server <url>"
    assert bool(private not in str(caught.value))


def test_version_is_a_single_version_not_arbitrary_child_output(vault_calls):
    vault_calls.output = "2026.1.0\n"
    assert vault_calls.module["version"]("synthetic", {}) == "2026.1.0"
    vault_calls.output = vault_calls.private
    with pytest.raises(vault_calls.module["SecretError"], match="invalid version"):
        vault_calls.module["version"]("synthetic", {})


def test_typing_operations_keep_arguments_environment_and_lock_timeout(vault_calls):
    state = vault_calls
    env = {"BW_SESSION": state.private, "BITWARDENCLI_APPDATA_DIR": "synthetic-data"}
    state.module["sync"]("synthetic", env)
    state.output = state.private + "\n\n"
    assert bool(state.module["get_field"]("synthetic", "password", "synthetic-item", env) == state.private)
    state.output = json.dumps([{"name": "synthetic-name", "login": {"password": state.private}}])
    assert state.module["list_items"]("synthetic", "query", env) == ["synthetic-name"]
    state.module["lock"]("synthetic", env)
    assert [argv for argv, _ in state.calls] == [["synthetic", "sync"], ["synthetic", "get", "password", "synthetic-item"],
                                               ["synthetic", "list", "items", "--search", "query"], ["synthetic", "lock"]]
    assert all(options["env"] is env and options["check"] is True for _, options in state.calls)
    assert [options["timeout"] for _, options in state.calls] == [60, 60, 60, 30]
    assert all(state.private not in argv for argv, _ in state.calls)


@pytest.mark.parametrize("payload", ["{}", "[null]", '[{"name": 3}]'])
def test_invalid_item_list_remains_loud(vault_calls, payload):
    vault_calls.output = payload
    with pytest.raises(vault_calls.module["SecretError"], match="invalid item list"):
        vault_calls.module["list_items"]("synthetic", "query", {})


def test_every_subprocess_has_checked_result_and_timeout():
    calls = [node for node in ast.walk(ast.parse(SCRIPT.read_text()))
             if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
             and isinstance(node.func.value, ast.Name) and node.func.value.id == "subprocess"]
    assert len(calls) == 1 and calls[0].func.attr == "run"
    keywords = {keyword.arg: keyword.value for keyword in calls[0].keywords}
    assert isinstance(keywords["check"], ast.Constant) and keywords["check"].value is True
    assert "timeout" in keywords
