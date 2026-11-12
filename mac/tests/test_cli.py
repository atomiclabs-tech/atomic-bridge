# no_parallel: isolated aiohttp test servers bind port 0.
from session_cases import credential_log_guard as credential_log_guard

import asyncio
import logging
import os
from pathlib import Path

import pytest
from aiohttp import ClientSession, ClientTimeout

from atomic_relay import cli


@pytest.fixture
def environment(tmp_path, monkeypatch):
    for name in list(os.environ):
        if name.startswith("ATOMIC_RELAY_"):
            monkeypatch.delenv(name)
    settings = {
        "ATOMIC_RELAY_HOST": "127.0.0.1", "ATOMIC_RELAY_PORT": "0",
        "ATOMIC_RELAY_SESSIONS_FILE": "", "ATOMIC_RELAY_ADMIN_SECRET_FILE": "", "ATOMIC_RELAY_LOG_FILE": str(tmp_path / "relay.log"),
        "ATOMIC_RELAY_MEDIA_DIR": str(tmp_path / "media"),
    }
    for name, value in settings.items():
        monkeypatch.setenv(name, value)
    monkeypatch.chdir(tmp_path)
    logger = logging.getLogger("atomic_relay")
    previous = logger.handlers[:], logger.level, logger.propagate
    logger.handlers = []
    yield settings
    for handler in logger.handlers:
        handler.close()
    logger.handlers, logger.level, logger.propagate = previous


def test_serve_main_starts_on_port_zero_and_serves_health(environment, monkeypatch):
    original = cli.serve
    observed = {}
    async def measure(config):
        stop = asyncio.Event()
        ready = asyncio.get_running_loop().create_future()
        server = asyncio.create_task(original(config, stop=stop, ready=ready))
        try:
            host, port = await asyncio.wait_for(ready, timeout=2)
            assert port > 0
            async with ClientSession(timeout=ClientTimeout(total=2)) as client:
                async with client.get(f"http://{host}:{port}/health") as response:
                    observed["status"] = response.status
                    observed["body"] = await response.json()
        finally:
            stop.set()
            await asyncio.wait_for(server, timeout=2)
    monkeypatch.setattr(cli, "serve", measure)
    assert cli.main(["serve"]) == 0
    assert observed["status"] == 200
    assert set(observed["body"]) == {
        "status", "version", "uptime_seconds",
    }
    assert observed["body"]["status"] == "ok"


@pytest.mark.parametrize("failure", ["unknown", "env-file", "sessions", "log-dir", "media-dir"])
def test_startup_failures_are_logged_and_nonzero(environment, tmp_path, monkeypatch, capsys, failure):
    args = ["serve"]
    if failure == "unknown":
        monkeypatch.setenv("ATOMIC_RELAY_UNKNOWN", "synthetic")
    elif failure == "env-file":
        path = tmp_path / "unreadable.env"
        args += ["--env-file", str(path)]
        def denied(self, **kwargs):
            raise PermissionError("synthetic unreadable env file")
        monkeypatch.setattr(Path, "read_text", denied)
    elif failure == "sessions":
        path = tmp_path / "sessions.json"
        path.write_text("{invalid")
        monkeypatch.setenv("ATOMIC_RELAY_SESSIONS_FILE", str(path))
    elif failure == "log-dir":
        def denied(self, *args, **kwargs):
            raise PermissionError("synthetic unwritable log directory")
        monkeypatch.setattr(Path, "mkdir", denied)
    else:
        path = tmp_path / "media"
        path.write_text("synthetic")
    assert cli.main(args) == 1
    stderr = capsys.readouterr().err
    assert "ERROR atomic_relay: component=cli operation=serve" in stderr
    assert "Traceback (most recent call last)" in stderr


def test_port_bind_failure_is_logged_with_cause(environment, monkeypatch, capsys):
    async def denied_start(self):
        raise OSError("synthetic port already in use")
    monkeypatch.setattr(cli.web.TCPSite, "start", denied_start)
    assert cli.main(["serve"]) == 1
    assert "synthetic port already in use" in capsys.readouterr().err
    assert "Traceback" in Path(environment["ATOMIC_RELAY_LOG_FILE"]).read_text()


def test_log_handler_failure_still_reaches_startup_stderr(environment, monkeypatch, capsys):
    def denied(*args):
        raise PermissionError("synthetic handler failure")
    monkeypatch.setattr(cli, "configure_logging", denied)
    assert cli.main(["serve"]) == 1
    assert "synthetic handler failure" in capsys.readouterr().err


def test_explicit_missing_env_file_fails(environment, tmp_path, capsys):
    assert cli.main(["serve", "--env-file", str(tmp_path / "missing.env")]) == 1
    assert "FileNotFoundError" in capsys.readouterr().err


def test_two_explicit_hosts_bind_real_port_zero_listeners(environment, monkeypatch, caplog):
    config = cli.load_config(environ={**environment, "ATOMIC_RELAY_HOST": "127.0.0.1, ::1"})
    assert config.hosts == ("127.0.0.1", "::1")
    original, runners = cli.web.AppRunner, []
    def record_runner(*args, **kwargs):
        runner = original(*args, **kwargs)
        runners.append(runner)
        return runner
    monkeypatch.setattr(cli.web, "AppRunner", record_runner)
    async def check():
        stop = asyncio.Event()
        ready = asyncio.get_running_loop().create_future()
        server = asyncio.create_task(cli.serve(config, stop=stop, ready=ready))
        try:
            await asyncio.wait_for(ready, timeout=2)
            addresses = runners[0].addresses
            assert len(addresses) == 2 and {item[0] for item in addresses} == set(config.hosts)
            async with ClientSession(timeout=ClientTimeout(total=2)) as client:
                for address in addresses:
                    host, port = address[:2]
                    authority = f"[{host}]" if ":" in host else host
                    async with client.get(f"http://{authority}:{port}/health") as response:
                        assert port > 0 and response.status == 200
                        assert (await response.json())["status"] == "ok"
            listening = [record for record in caplog.records if "operation=serve listening" in record.getMessage()]
            assert len(listening) == 1
            assert all(host in listening[0].getMessage() for host in config.hosts)
        finally:
            stop.set()
            await asyncio.wait_for(server, timeout=2)
    with caplog.at_level(logging.INFO, logger="atomic_relay"):
        asyncio.run(check())


def test_two_explicit_hosts_second_bind_failure_cleans_first_listener(environment, monkeypatch, capsys):
    monkeypatch.setenv("ATOMIC_RELAY_HOST", "127.0.0.1, ::1")
    assert cli.load_config().hosts == ("127.0.0.1", "::1")
    original, runner_factory, runners, calls = cli.web.TCPSite.start, cli.web.AppRunner, [], []
    def record_runner(*args, **kwargs):
        runner = runner_factory(*args, **kwargs)
        runners.append(runner)
        return runner
    async def fail_second(site):
        calls.append(site)
        if len(calls) == 2:
            raise OSError("synthetic second bind failure")
        await original(site)
    monkeypatch.setattr(cli.web.TCPSite, "start", fail_second)
    monkeypatch.setattr(cli.web, "AppRunner", record_runner)
    assert cli.main(["serve"]) == 1
    assert len(calls) == 2 and len(runners) == 1 and not runners[0].addresses
    assert "synthetic second bind failure" in capsys.readouterr().err
