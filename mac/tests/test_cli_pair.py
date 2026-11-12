# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import argparse
import io
import json
import logging
from pathlib import Path

import pytest
from aiohttp import web

from atomic_relay import cli
from atomic_relay import cli_remote
from atomic_relay.ops.cutover_plan import NEW_AGENT
from atomic_relay.ops.layout import Layout
from session_cases import application, cli_env


@pytest.fixture(autouse=True)
def isolated_home(tmp_path, monkeypatch):
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: tmp_path / "isolated-home"))


def test_guided_pair_parser_accepts_optional_explicit_code():
    parser = argparse.ArgumentParser()
    cli_remote.add_commands(parser.add_subparsers(dest="command", required=True))
    args = parser.parse_args(["pair", "--address", "100.64.1.2", "--yes", "--no-wait", "--no-qr"])
    assert args.register_code is None and args.yes and args.no_wait and args.no_qr


def test_pair_help_exits_before_any_default_live_path_resolution(monkeypatch, capsys):
    def forbidden(*args):
        raise AssertionError("help resolved a default path")
    monkeypatch.setattr(Path, "home", forbidden)
    monkeypatch.setattr(Path, "exists", forbidden)
    with pytest.raises(SystemExit) as caught:
        cli.main(["pair", "--help"])
    assert caught.value.code == 0
    capsys.readouterr()


def test_guided_cli_block_has_no_extra_json_and_error_json_uses_stderr(tmp_path, monkeypatch):
    from atomic_relay.ops.commands import StepFailure
    home = Path.home()
    env_file = cli_env(tmp_path)
    env_file.write_text(env_file.read_text() + "ATOMIC_RELAY_PHONE_URL=wss://relay.example.com\n")
    monkeypatch.setattr(cli, "configure_startup_logging", lambda: None)
    class Runner:
        def run(self, *args, **kwargs):
            return "loaded"
    monkeypatch.setattr(cli_remote, "CommandRunner", Runner)
    calls = []
    def request(port, path, body=None, *, authorization=None, timeout=5):
        calls.append((path, authorization is not None, timeout))
        return (200, {"status": "ok"}) if path == "/health" else (200, {"ok": True, "code": body["code"]})
    monkeypatch.setattr(cli_remote, "_request", request)
    output, errors = io.StringIO(), io.StringIO()
    monkeypatch.setattr("sys.stdout", output)
    monkeypatch.setattr("sys.stderr", errors)
    result = cli.main(["pair", "--env-file", str(env_file), "--no-wait", "--no-qr"])
    visible = result == 0 and output.getvalue().startswith("Relay address for the phone:")
    assert visible
    extra_json = any(line.startswith("{") for line in output.getvalue().splitlines())
    assert not extra_json and not errors.getvalue()
    assert calls == [("/health", False, 5), ("/pairing/register", True, 5)]
    def unavailable(*args, **kwargs):
        raise StepFailure(("synthetic-command",), 9, "synthetic failure")
    monkeypatch.setattr(Runner, "run", unavailable)
    output.seek(0)
    output.truncate()
    assert cli.main(["pair", "--env-file", str(env_file), "--no-wait"]) == 2
    assert not output.getvalue() and json.loads(errors.getvalue())["ok"] is False
    assert not home.exists()


def test_guided_yes_uses_real_setup_once_and_new_codes_on_repeat(tmp_path, monkeypatch):
    from ops_cases import FakeClock
    home = tmp_path / "setup-home"
    layout = Layout(home, 42)
    layout.service.mkdir(parents=True)
    env_file = cli_env(layout.service)
    plist = layout.plist(NEW_AGENT)
    plist.parent.mkdir(parents=True)
    plist.write_text("isolated plist")
    class Runner:
        def __init__(self):
            self.calls = []
        def require(self, names):
            assert names == ("launchctl",)
        def run(self, argv, **kwargs):
            self.calls.append(tuple(argv))
            return "loaded"
    codes, authorized = [], []
    def request(port, path, body=None, *, authorization=None, timeout=5):
        authorized.append(path == "/health" or authorization is not None)
        if path == "/health":
            return 200, {"status": "ok"}
        codes.append(body["code"])
        return 200, {"ok": True, "code": body["code"]}
    monkeypatch.setattr(cli_remote, "_request", request)
    runner, clock, output = Runner(), FakeClock(), []
    counter = iter(range(12))
    monkeypatch.setattr("atomic_relay.pairing_cli.pair_guided.secrets.choice", lambda alphabet: alphabet[next(counter)])
    parser = argparse.ArgumentParser()
    cli_remote.add_commands(parser.add_subparsers(dest="command", required=True))
    args = parser.parse_args(["pair", "--env-file", str(env_file), "--address", "100.64.1.2",
                              "--yes", "--no-wait", "--no-qr"])
    def forbidden(question):
        raise AssertionError("--address/--yes prompted")
    for _ in range(2):
        assert cli_remote.pair(args, runner=runner, layout=layout, ask=forbidden, emit=output.append,
                               clock=clock.clock, sleep=clock.sleep) == (0, None)
    distinct = len(codes) == 2 and codes[0] != codes[1]
    assert distinct and all(authorized)
    assert len(list(layout.service.glob("relay.env.bak-*"))) == 1
    assert sum(argv[1] == "bootout" for argv in runner.calls) == 1


@pytest.mark.parametrize("explicit_env_file", [False, True])
def test_pair_cli_actual_loopback_defaults_no_extra_code_output(tmp_path, monkeypatch, capsys, caplog, explicit_env_file):
    monkeypatch.setattr(cli, "configure_startup_logging", lambda: None)
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    env_file = cli_env(tmp_path)
    monkeypatch.chdir(tmp_path)
    async def check():
        async with application(tmp_path) as (app, client):
            from atomic_relay.web.runtime import RUNTIME
            with caplog.at_level(logging.DEBUG):
                args = ["pair", "--register-code", "io01az", "--port", str(client.port)]
                if explicit_env_file:
                    args += ["--env-file", str(env_file)]
                result = await asyncio.to_thread(cli.main, args)
            assert result == 0
            policy = app[RUNTIME].pairing.consume_code("IO01AZ")
            assert policy.ttl_seconds == 30 * 86400 and policy.grants is None
    asyncio.run(check())
    output = capsys.readouterr()
    assert output.out.splitlines() == [json.dumps({"ok": True})]
    assert "IO01AZ" not in output.out + output.err + caplog.text and "io01az" not in output.out + output.err + caplog.text
    secret = (tmp_path / "admin.secret").read_text().strip()
    assert secret not in output.out + output.err + caplog.text


def test_pair_cli_refusal_validation_and_unreachable_exit_codes(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(cli, "configure_startup_logging", lambda: None)
    env_file = cli_env(tmp_path)
    async def check():
        async with application(tmp_path) as (_, client):
            assert await asyncio.to_thread(cli.main, ["pair", "--register-code", "BAD", "--port", str(client.port), "--env-file", str(env_file)]) == 1
            return client.port
    port = asyncio.run(check())
    assert cli.main(["pair", "--register-code", "ABC123", "--port", str(port), "--env-file", str(env_file)]) == 2
    output = capsys.readouterr()
    assert "ABC123" not in output.out + output.err


@pytest.mark.parametrize("explicit_env_file", [False, True])
def test_status_cli_exit_codes_and_server_json(tmp_path, monkeypatch, capsys, explicit_env_file):
    monkeypatch.setattr(cli, "configure_startup_logging", lambda: None)
    env_file = cli_env(tmp_path)
    monkeypatch.chdir(tmp_path)
    async def check():
        async with application(tmp_path) as (_, client):
            # Status belongs to the later device registry; test the CLI's public HTTP contract here.
            port = client.port
        from aiohttp import ClientTimeout
        from aiohttp.test_utils import TestClient, TestServer
        app = web.Application()
        connected = [True]
        async def status(request):
            expected = (tmp_path / "admin.secret").read_text().strip()
            accepted = request.headers.get("Authorization") == "Bearer " + expected
            assert accepted
            return web.json_response({"phone_connected": connected[0]}, status=200 if connected[0] else 503)
        app.router.add_get("/bridge/status", status)
        client = TestClient(TestServer(app, port=0), timeout=ClientTimeout(total=2))
        await asyncio.wait_for(client.start_server(), timeout=2)
        try:
            args = ["status", "--port", str(client.port)]
            if explicit_env_file:
                args += ["--env-file", str(env_file)]
            assert await asyncio.to_thread(cli.main, args) == 0
            connected[0] = False
            assert await asyncio.to_thread(cli.main, args) == 2
        finally:
            await asyncio.wait_for(client.close(), timeout=2)
        return port
    port = asyncio.run(check())
    assert cli.main(["status", "--port", str(port), "--env-file", str(env_file)]) == 1
    lines = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert [line["phone_connected"] for line in lines] == [True, False, False]
