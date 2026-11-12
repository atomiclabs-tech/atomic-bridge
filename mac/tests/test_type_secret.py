# no_parallel: executes an isolated fake vault CLI under tmp_path.
import json
import logging
import runpy
import shlex
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "tools/type_secret.py"
NO_SESSION = "Set BW_SESSION or ATOMIC_BW_SESSION_CMD before running tools/type_secret.py"
FAKE = '''
import json
import os
import sys
from pathlib import Path

args = sys.argv[1:]
session = os.environ["TEST_SESSION_VALUE"]
secret = os.environ["TEST_SECRET_VALUE"]
record = {
    "argv": ["[private]" if value in (session, secret) else value for value in args],
    "session_in_argv": session in args,
    "secret_in_argv": secret in args,
    "session_env_matches": os.environ.get("BW_SESSION") == session,
    "appdata": os.environ.get("BITWARDENCLI_APPDATA_DIR"),
}
with Path(os.environ["TEST_VAULT_LOG"]).open("a") as output:
    output.write(json.dumps(record) + "\\n")
operation = args[0]
if operation == os.environ.get("TEST_FAIL"):
    print(secret)
    print(session, file=sys.stderr)
    raise SystemExit(3)
if operation == "session":
    print(os.environ.get("TEST_SESSION_OUTPUT", session))
elif operation == "--version":
    print(os.environ.get("TEST_VERSION_OUTPUT", "2026.1.0"))
elif operation == "status":
    print(os.environ.get("TEST_STATUS_OUTPUT", json.dumps({
        "serverUrl": "https://vault.example.com", "status": "locked", "userEmail": "x@y"
    })))
elif operation == "get":
    print(os.environ.get("TEST_GET_OUTPUT", secret))
elif operation == "list":
    print(os.environ.get("TEST_LIST_OUTPUT", json.dumps([
        {"id": "synthetic-id", "name": "synthetic item", "login": {"username": secret, "uris": [{"uri": session}]}}
    ])))
'''


@pytest.fixture
def vault(tmp_path, monkeypatch):
    binary = tmp_path / "fake vault"
    binary.write_text(f"#!{sys.executable}\n" + FAKE)
    binary.chmod(0o700)
    session = "synthetic-" + "s" * 48
    secret = 'synthetic-"quoted\\value\n' + "p" * 12
    log = tmp_path / "vault.jsonl"
    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    monkeypatch.setenv("BW", str(binary))
    monkeypatch.setenv("TEST_SESSION_VALUE", session)
    monkeypatch.setenv("TEST_SECRET_VALUE", secret)
    monkeypatch.setenv("TEST_VAULT_LOG", str(log))
    for key in ("BW_SESSION", "ATOMIC_BW_SESSION_CMD", "BITWARDENCLI_APPDATA_DIR", "TEST_FAIL", "TEST_SESSION_OUTPUT", "TEST_GET_OUTPUT", "TEST_LIST_OUTPUT", "TEST_STATUS_OUTPUT", "TEST_VERSION_OUTPUT"):
        monkeypatch.delenv(key, raising=False)
    original = subprocess.run
    calls, tools = [], []
    state = SimpleNamespace(binary=binary, session=session, secret=secret, log=log, tools=tools, calls=calls,
                            failure=None, result=None, timeout=None, command_error=None)

    def run(argv, **kwargs):
        argv = list(argv)
        operation = "session" if argv[0] == "bash" else argv[1] if len(argv) > 1 else ""
        # The base's owner session path is intercepted before any execution or read.
        assert argv[0] in (str(binary), "bw", "bash")
        calls.append({"operation": operation, "timeout": kwargs.get("timeout"), "checked": kwargs.get("check", False)})
        if operation == state.timeout:
            raise subprocess.TimeoutExpired(["synthetic-command"], 0.001, output=secret, stderr=session)
        if operation == "sync" and state.command_error:
            raise state.command_error
        if argv[0] in ("bash", "bw"):
            argv = [str(binary), operation, *([] if operation == "session" else argv[2:])]
        return original(argv, **kwargs)

    def dispatch(name, args):
        tools.append((name, args))
        if state.failure:
            raise state.failure
        if name == "android_ping" and state.result is None:
            return json.dumps({"pong": True})
        return state.result if state.result is not None else json.dumps({"success": True, "echo": args["text"], "session": state.session})

    monkeypatch.setattr(subprocess, "run", run)
    monkeypatch.setitem(sys.modules, "call_tool", SimpleNamespace(load_context=lambda: SimpleNamespace(dispatch_tool=dispatch)))
    state.module = runpy.run_path(str(SCRIPT))
    state.records = lambda: [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
    return state


def _invoke(vault, capsys, args):
    status = vault.module["main"](args)
    captured = capsys.readouterr()
    return status, captured.out, captured.err


def _configure(vault, monkeypatch, owned):
    if owned:
        monkeypatch.setenv("ATOMIC_BW_SESSION_CMD", shlex.join([str(vault.binary), "session", "quoted argument"]))
    else:
        monkeypatch.setenv("BW_SESSION", vault.session)


def _private_free(vault, output):
    return vault.secret not in output and vault.session not in output and json.dumps(vault.secret)[1:-1] not in output


@pytest.mark.parametrize("owned", [False, True])
def test_attack12_session_is_only_in_environment_and_never_in_argv(vault, monkeypatch, capsys, owned):
    _configure(vault, monkeypatch, owned)
    status, output, error = _invoke(vault, capsys, ["synthetic item"])
    assert status == 0
    records = [item for item in vault.records() if item["argv"][0] != "session"]
    assert all("--session" not in item["argv"] for item in records)
    assert all(not item["session_in_argv"] and not item["secret_in_argv"] for item in records)
    assert all(item["session_env_matches"] for item in records)
    assert _private_free(vault, output + error)
    assert vault.tools[0][0] == "android_type" and vault.tools[0][1]["text"] == vault.secret


@pytest.mark.parametrize("field", ["password", "username", "totp"])
@pytest.mark.parametrize("no_clear", [False, True])
def test_field_and_clear_options_preserve_typing_and_redact_json(vault, monkeypatch, capsys, field, no_clear):
    _configure(vault, monkeypatch, False)
    status, output, error = _invoke(vault, capsys, ["synthetic item", "--field", field, *(["--no-clear"] if no_clear else [])])
    assert status == 0 and error == ""
    assert json.loads(output) == {"success": True, "echo": "***", "session": "***"}
    assert vault.tools == [("android_type", {"text": vault.secret, "clear_first": not no_clear})]
    assert vault.records()[1]["argv"] == ["get", field, "synthetic item"]
    assert [call["timeout"] for call in vault.calls] == [60, 60]
    assert all(call["checked"] for call in vault.calls)


@pytest.mark.parametrize("owned", [False, True])
def test_lock_ownership_and_optional_appdata_passthrough(vault, monkeypatch, capsys, owned, tmp_path):
    _configure(vault, monkeypatch, owned)
    appdata = str(tmp_path / "configured data")
    monkeypatch.setenv("BITWARDENCLI_APPDATA_DIR", appdata)
    assert _invoke(vault, capsys, ["synthetic item"])[0] == 0
    assert all(item["appdata"] == appdata for item in vault.records())
    operations = [call["operation"] for call in vault.calls]
    assert operations == (["session", "sync", "get", "lock"] if owned else ["sync", "get"])
    assert vault.calls[-1]["timeout"] == (30 if owned else 60)


def test_no_appdata_default_and_default_bw_uses_path(vault, monkeypatch, capsys):
    _configure(vault, monkeypatch, False)
    monkeypatch.delenv("BW")
    assert _invoke(vault, capsys, ["synthetic item"])[0] == 0
    assert all(item["appdata"] is None for item in vault.records())


def test_caller_session_is_used_as_is_and_command_is_not_run(vault, monkeypatch, capsys):
    vault.session = " " + vault.session + "\n"
    monkeypatch.setenv("TEST_SESSION_VALUE", vault.session)
    monkeypatch.setenv("BW_SESSION", vault.session)
    monkeypatch.setenv("ATOMIC_BW_SESSION_CMD", "not-an-executed-command")
    assert _invoke(vault, capsys, ["synthetic item"])[0] == 0
    assert [call["operation"] for call in vault.calls] == ["sync", "get"]
    assert all(item["session_env_matches"] for item in vault.records())


def test_missing_session_is_fixed_nonzero_without_external_calls(vault, capsys, caplog):
    with caplog.at_level(logging.ERROR, logger="atomic_bridge"):
        status, output, error = _invoke(vault, capsys, ["synthetic item"])
    assert status == 1 and output == "" and error.strip() == NO_SESSION
    assert vault.calls == [] and vault.tools == []
    assert any(record.exc_info for record in caplog.records)


@pytest.mark.parametrize("configuration,message", [
    ({"BW_SESSION": ""}, "BW_SESSION is empty"),
    ({"ATOMIC_BW_SESSION_CMD": "'"}, "ATOMIC_BW_SESSION_CMD must be a valid command line"),
    ({"ATOMIC_BW_SESSION_CMD": " "}, NO_SESSION),
    ({"BW": "", "BW_SESSION": "synthetic"}, "BW must name an executable"),
])
def test_bad_configuration_stops_without_external_calls(vault, monkeypatch, capsys, configuration, message):
    for key, value in configuration.items():
        monkeypatch.setenv(key, value)
    status, output, error = _invoke(vault, capsys, ["synthetic item"])
    assert status == 1 and output == "" and error.strip() == message
    assert vault.calls == []


@pytest.mark.parametrize("operation", ["sync", "get", "list", "lock"])
@pytest.mark.parametrize("owned", [False, True])
def test_failing_bw_is_nonzero_private_and_locks_only_owned_sessions(vault, monkeypatch, capsys, caplog, operation, owned):
    _configure(vault, monkeypatch, owned)
    monkeypatch.setenv("TEST_FAIL", operation)
    status, output, error = _invoke(vault, capsys, ["synthetic item", *(["--list"] if operation == "list" else [])])
    expected_failure = operation != "lock" or owned
    assert status == int(expected_failure)
    assert _private_free(vault, output + error + caplog.text)
    assert ("bw " + operation + " failed") in error if expected_failure else error == ""
    assert sum(call["operation"] == "lock" for call in vault.calls) == int(owned)


@pytest.mark.parametrize("failure", ["failed", "empty", "timeout"])
def test_session_command_failure_is_nonzero_without_lock(vault, monkeypatch, capsys, caplog, failure):
    _configure(vault, monkeypatch, True)
    if failure == "failed":
        monkeypatch.setenv("TEST_FAIL", "session")
    elif failure == "empty":
        monkeypatch.setenv("TEST_SESSION_OUTPUT", "")
    else:
        vault.timeout = "session"
    status, output, error = _invoke(vault, capsys, ["synthetic item"])
    assert status == 1 and output == "" and "Session command" in error
    assert [call["operation"] for call in vault.calls] == ["session"]
    assert _private_free(vault, error + caplog.text)


@pytest.mark.parametrize("operation", ["sync", "get", "lock"])
def test_timeouts_are_checked_private_failures_with_owned_cleanup(vault, monkeypatch, capsys, caplog, operation):
    _configure(vault, monkeypatch, True)
    vault.timeout = operation
    status, output, error = _invoke(vault, capsys, ["synthetic item"])
    assert status == 1 and "bw " + operation + " failed" in error
    assert sum(call["operation"] == "lock" for call in vault.calls) == 1
    assert _private_free(vault, output + error + caplog.text)


def test_list_prints_matching_names_without_login_fields_or_typing(vault, monkeypatch, capsys):
    _configure(vault, monkeypatch, True)
    status, output, error = _invoke(vault, capsys, ["synthetic", "--list"])
    assert status == 0 and output == "synthetic item\n" and error == ""
    assert vault.tools == []
    assert vault.records()[2]["argv"] == ["list", "items", "--search", "synthetic"]


@pytest.mark.parametrize("response", ["{", "{}", "[null]"])
def test_bad_vault_list_is_nonzero_without_private_output(vault, monkeypatch, capsys, caplog, response):
    _configure(vault, monkeypatch, True)
    monkeypatch.setenv("TEST_LIST_OUTPUT", response)
    status, output, error = _invoke(vault, capsys, ["synthetic", "--list"])
    assert status == 1 and output == "" and error
    assert vault.calls[-1]["operation"] == "lock"
    assert _private_free(vault, error + caplog.text)


def test_empty_field_and_tool_failure_are_nonzero_and_private(vault, monkeypatch, capsys, caplog):
    _configure(vault, monkeypatch, True)
    monkeypatch.setenv("TEST_GET_OUTPUT", "")
    status, output, error = _invoke(vault, capsys, ["synthetic item"])
    assert status == 1 and output == "" and error.strip() == "Item has an empty password"
    monkeypatch.delenv("TEST_GET_OUTPUT")
    vault.failure = RuntimeError(vault.secret + vault.session)
    status, output, error = _invoke(vault, capsys, ["synthetic item"])
    assert status == 1 and output == "" and error.strip() == "Type secret failed: RuntimeError"
    assert _private_free(vault, error + caplog.text)


@pytest.mark.parametrize("result", ['{"success": false, "error": "synthetic failure"}', '[]', '{'])
def test_failed_or_malformed_tool_result_is_nonzero(vault, monkeypatch, capsys, caplog, result):
    _configure(vault, monkeypatch, True)
    vault.result = result
    status, output, error = _invoke(vault, capsys, ["synthetic item"])
    assert status == 1 and ("synthetic failure" in output or error)
    assert vault.calls[-1]["operation"] == "lock"
    assert _private_free(vault, output + error + caplog.text)


@pytest.mark.parametrize("kind", [FileNotFoundError, PermissionError, ValueError])
def test_unavailable_or_invalid_subprocess_is_fixed_private_failure(vault, monkeypatch, capsys, caplog, kind):
    _configure(vault, monkeypatch, True)
    vault.command_error = kind(vault.secret + vault.session)
    status, output, error = _invoke(vault, capsys, ["synthetic item"])
    assert status == 1 and output == "" and error.strip() == "bw sync failed"
    assert vault.calls[-1]["operation"] == "lock"
    assert _private_free(vault, error + caplog.text)


def test_check_locked_prints_four_safe_lines_and_missing_session(vault, capsys, caplog):
    status, output, error = _invoke(vault, capsys, ["--check"])
    assert status == 1 and error.strip() == NO_SESSION
    assert output.splitlines() == [f"Bitwarden CLI: {vault.binary}, version 2026.1.0",
                                   "Server: https://vault.example.com", "Vault: locked", "Phone: online"]
    assert "x@y" not in output + error + caplog.text
    assert _private_free(vault, output + error + caplog.text)
    assert vault.tools == [("android_ping", {})]
    assert [call["operation"] for call in vault.calls] == ["--version", "status"]
    assert all(call["checked"] and call["timeout"] == 60 for call in vault.calls)
    assert all(not item["session_env_matches"] for item in vault.records())


@pytest.mark.parametrize("state, expected, step", [
    ("unauthenticated", 1, "Run: bw login"), ("unlocked", 0, ""),
])
def test_check_without_session_reports_vault_state(vault, monkeypatch, capsys, state, expected, step):
    monkeypatch.setenv("TEST_STATUS_OUTPUT", json.dumps({"status": state, "serverUrl": None, "userEmail": "x@y"}))
    status, output, error = _invoke(vault, capsys, ["--check"])
    assert status == expected and error.strip() == step
    assert f"Vault: {state}\nPhone: online\n" in output
    assert "Server: bitwarden.com (default)\n" in output
    assert vault.tools == [("android_ping", {})]
    assert [call["operation"] for call in vault.calls] == ["--version", "status"]


@pytest.mark.parametrize("owned", [False, True])
def test_check_configured_session_is_never_acquired_or_locked(vault, monkeypatch, capsys, owned):
    _configure(vault, monkeypatch, owned)
    status, output, error = _invoke(vault, capsys, ["--check"])
    assert status == 0 and error == "" and "Phone: online" in output
    assert [call["operation"] for call in vault.calls] == ["--version", "status"]
    assert vault.tools == [("android_ping", {})]
    assert _private_free(vault, output + error)


@pytest.mark.parametrize("failure", ["returned", "raised", "malformed", "unconfirmed"])
def test_check_phone_offline_uses_fixed_private_reason(vault, monkeypatch, capsys, caplog, failure):
    monkeypatch.setenv("TEST_STATUS_OUTPUT", json.dumps({"status": "unlocked"}))
    if failure == "raised":
        vault.failure = RuntimeError(vault.secret + vault.session)
    else:
        vault.result = {"returned": json.dumps({"error": vault.secret}),
                        "malformed": "{", "unconfirmed": "{}"}[failure]
    status, output, error = _invoke(vault, capsys, ["--check"])
    assert status == 1 and "Phone: offline (android_ping failed)\n" in output
    assert error.strip() == "Phone check failed"
    assert _private_free(vault, output + error + caplog.text)
    assert any(record.exc_info for record in caplog.records)


def test_check_failing_status_never_exposes_child_output(vault, monkeypatch, capsys, caplog):
    monkeypatch.setenv("TEST_FAIL", "status")
    status, output, error = _invoke(vault, capsys, ["--check"])
    assert status == 1 and error.strip() == "bw status failed"
    assert _private_free(vault, output + error + caplog.text)
    assert vault.tools == []
    assert any(record.exc_info for record in caplog.records)


@pytest.mark.parametrize("operation", ["--version", "status"])
def test_check_subprocess_timeout_is_private_and_bounded(vault, capsys, caplog, operation):
    vault.timeout = operation
    status, output, error = _invoke(vault, capsys, ["--check"])
    assert status == 1 and error.strip() == f"bw {operation} failed"
    assert _private_free(vault, output + error + caplog.text)
    assert all(call["checked"] and call["timeout"] == 60 for call in vault.calls)


def test_check_missing_cli_names_install_without_execution(vault, monkeypatch, capsys):
    monkeypatch.setenv("BW", str(vault.binary.parent / "missing-cli"))
    status, output, error = _invoke(vault, capsys, ["--check"])
    assert status == 1 and output == "" and error.strip() == "Bitwarden CLI not found; install bw or set BW"
    assert vault.calls == [] and vault.tools == []


def test_check_wrong_server_names_config_step_without_echo(vault, monkeypatch, capsys, caplog):
    monkeypatch.setenv("TEST_STATUS_OUTPUT", json.dumps({"status": "unlocked", "serverUrl": vault.secret}))
    status, output, error = _invoke(vault, capsys, ["--check"])
    assert status == 1 and error.strip() == "Run: bw config server <url>"
    assert _private_free(vault, output + error + caplog.text)


def test_check_first_missing_step_precedes_offline_phone(vault, capsys):
    vault.result = json.dumps({"error": "unavailable"})
    status, output, error = _invoke(vault, capsys, ["--check"])
    assert status == 1 and error.strip() == NO_SESSION
    assert "Phone: offline (android_ping failed)" in output
