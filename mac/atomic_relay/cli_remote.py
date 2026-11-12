# Ported from plugin/pair.py and plugin/status.py at upstream commit da782a8.
"""Loopback HTTP commands for pairing and cached phone status."""

import argparse
from dataclasses import replace
import json
import logging
import os
import re
import time
import urllib.error
import urllib.request
from pathlib import Path

from atomic_relay.config import DEFAULTS, load_config
from atomic_relay.sessions.pairing import normalize_code
from atomic_relay.ops.commands import CommandRunner
from atomic_relay.ops.layout import Layout
from atomic_relay.ops.pair_setup_exec import PairSetup
from atomic_relay.pairing_cli.pair_guided import PairingArgumentsError, PairProbe, run_guided

logger = logging.getLogger("atomic_relay")


def parse_duration(value: str) -> int:
    normalized = value.strip().lower()
    if normalized == "never":
        return 0
    match = re.fullmatch(r"([0-9]+)([smhdwy]?)", normalized)
    if match is None:
        raise ValueError("duration must be never or non-negative seconds with an optional unit")
    scales = {"": 1, "s": 1, "m": 60, "h": 3600, "d": 86400, "w": 604800, "y": 31536000}
    return int(match[1]) * scales[match[2]]


def parse_grants(value: str) -> dict[str, int]:
    result = {}
    for entry in value.split(","):
        key, separator, seconds = entry.strip().partition("=")
        if not key or not separator:
            raise ValueError("grants must use channel=duration")
        result[key.strip()] = parse_duration(seconds)
    return result


def _port(value):
    number = int(value)
    if not 1 <= number <= 65535:
        raise argparse.ArgumentTypeError("port must be between 1 and 65535")
    return number


def add_commands(commands):
    pair = commands.add_parser("pair", help="guide pairing the phone app")
    pair.add_argument("--register-code")
    pair.add_argument("--ttl", default="30d")
    pair.add_argument("--grants")
    pair.add_argument("--port", type=_port)
    pair.add_argument("--env-file", type=Path)
    pair.add_argument("--address")
    pair.add_argument("--yes", action="store_true")
    pair.add_argument("--no-wait", action="store_true")
    pair.add_argument("--no-qr", action="store_true")
    status = commands.add_parser("status", help="show cached phone status")
    status.add_argument("--port", type=_port, default=int(DEFAULTS["ATOMIC_RELAY_PORT"]))
    status.add_argument("--env-file", type=Path)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Pairing data must stay on the explicit loopback endpoint.
        return None


def _request(port, path, body=None, *, authorization=None, timeout=5):
    headers = {"Content-Type": "application/json"}
    if authorization is not None:
        headers["Authorization"] = authorization
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}", data=None if body is None else json.dumps(body).encode(),
        headers=headers, method="GET" if body is None else "POST",
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())
    try:
        response = opener.open(request, timeout=timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.status
        data = json.loads(response.read().decode("utf-8"))
    if not isinstance(data, dict):
        raise ValueError("server returned non-object JSON")
    return status, data


def _explicit_pair(args) -> tuple[int, dict]:
    try:
        code = normalize_code(args.register_code)
        body = {"code": code, "ttl_seconds": parse_duration(args.ttl)}
        if args.grants is not None:
            body["grants"] = parse_grants(args.grants)
    except ValueError:
        logger.exception("component=cli operation=pair invalid arguments")
        return 1, {"ok": False, "error": "invalid pairing arguments"}
    try:
        authorization = load_config(args.env_file).admin_secret.authorization()
        port = args.port if args.port is not None else int(DEFAULTS["ATOMIC_RELAY_PORT"])
        health_status, health = _request(port, "/health")
        if health_status != 200 or health.get("status") != "ok":
            return 1, {"ok": False, "error": "server health check refused"}
        status, result = _request(port, "/pairing/register", body, authorization=authorization)
        if status == 200 and result.get("ok") is True and result.get("code") == code:
            return 0, {"ok": True}
        error = result.get("error", "server refused pairing")
        safe = (error.replace(code, "[redacted]").replace(args.register_code, "[redacted]")
                .replace(authorization[7:], "[redacted]")) if isinstance(error, str) else "server refused pairing"
        return 1, {"ok": False, "error": safe}
    except (urllib.error.URLError, OSError, ValueError):
        logger.exception("component=cli operation=pair server unavailable")
        return 2, {"ok": False, "error": "server unreachable or invalid response"}


def pair(args, *, runner=None, layout=None, ask=None, emit=None, clock=time.monotonic, sleep=time.sleep):
    if args.env_file is None:
        installed = Path.home() / "services/atomic-bridge/relay.env"
        args.env_file = installed if installed.exists() else Path("relay.env")
    if args.register_code is not None:
        return _explicit_pair(args)
    try:
        ttl = parse_duration(args.ttl)
        grants = parse_grants(args.grants) if args.grants is not None else None
    except ValueError as error:
        safe = RuntimeError("pairing arguments invalid; sensitive details withheld")
        logger.exception("component=cli operation=pair invalid arguments",
                         exc_info=(RuntimeError, safe, error.__traceback__))
        return 1, {"ok": False, "error": "invalid pairing arguments"}
    try:
        config = load_config(args.env_file)
        authorization = config.admin_secret.authorization()
        port = args.port if args.port is not None else config.port
        config = replace(config, port=port)
        runner = runner if runner is not None else CommandRunner()
        layout = layout if layout is not None else Layout(Path.home(), os.getuid())
        ask, emit = ask if ask is not None else input, emit if emit is not None else print
        def request(path, body=None, *, timeout=5):
            status, data = _request(port, path, body, authorization=None if path == "/health" else authorization,
                                    timeout=timeout)
            if isinstance(data.get("error"), str):
                data = {**data, "error": data["error"].replace(authorization[7:], "[redacted]")}
            return status, data
        probe = PairProbe(request)
        setup = PairSetup(layout, runner, probe, clock, sleep, emit)
        return run_guided(args, config, layout=layout, runner=runner, setup=setup, probe=probe,
                          ask=ask, emit=emit, clock=clock, sleep=sleep, ttl_seconds=ttl, grants=grants)
    except PairingArgumentsError as error:
        safe = RuntimeError("pairing failed; sensitive details withheld")
        logger.exception("component=cli operation=pair invalid arguments or response",
                         exc_info=(RuntimeError, safe, error.__traceback__))
        return 1, {"ok": False, "error": "invalid pairing arguments or response"}
    except Exception as error:
        safe = RuntimeError("pairing failed; sensitive details withheld")
        logger.exception("component=cli operation=pair server unavailable",
                         exc_info=(RuntimeError, safe, error.__traceback__))
        return 2, {"ok": False, "error": "server unreachable or invalid response"}


def status(args) -> tuple[int, dict]:
    try:
        authorization = load_config(args.env_file).admin_secret.authorization()
        response, data = _request(args.port, "/bridge/status", authorization=authorization)
        if response not in {200, 503}:
            raise ValueError("server refused status request")
        return (0 if response == 200 else 2), data
    except (urllib.error.URLError, OSError, ValueError):
        logger.exception("component=cli operation=status server unavailable")
        return 1, {"phone_connected": False, "error": "server unreachable or invalid response"}
