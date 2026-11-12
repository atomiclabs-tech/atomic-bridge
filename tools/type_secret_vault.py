"""Bounded Bitwarden calls that keep child output out of error messages."""

import json
import re
import shlex
import subprocess
from urllib.parse import urlsplit

NO_SESSION = "Set BW_SESSION or ATOMIC_BW_SESSION_CMD before running tools/type_secret.py"


class SecretError(ValueError):
    """A failure containing fixed explanatory text, never vault output."""


def _run(argv, env, operation, *, timeout=60):
    try:
        return subprocess.run(argv, env=env, capture_output=True, text=True,
                              check=True, timeout=timeout).stdout
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise SecretError(f"{operation} failed") from error


def session(env):
    if "BW_SESSION" in env:
        if not env["BW_SESSION"]:
            raise SecretError("BW_SESSION is empty")
        return env["BW_SESSION"], False
    command = env.get("ATOMIC_BW_SESSION_CMD")
    if not command:
        raise SecretError(NO_SESSION)
    try:
        argv = shlex.split(command)
    except ValueError as error:
        raise SecretError("ATOMIC_BW_SESSION_CMD must be a valid command line") from error
    if not argv:
        raise SecretError(NO_SESSION)
    value = _run(argv, env, "Session command").strip()
    if not value:
        raise SecretError("Session command returned an empty session")
    return value, True


def sync(binary, env):
    _run([binary, "sync"], env, "bw sync")


def get_field(binary, field, item, env):
    return _run([binary, "get", field, item], env, "bw get").rstrip("\n")


def list_items(binary, item, env):
    items = json.loads(_run([binary, "list", "items", "--search", item], env, "bw list"))
    if not isinstance(items, list) or any(
            not isinstance(value, dict) or not isinstance(value.get("name"), str) for value in items):
        raise SecretError("bw list returned an invalid item list")
    return [value["name"] for value in items]


def lock(binary, env):
    _run([binary, "lock"], env, "bw lock", timeout=30)


def version(binary, env):
    value = _run([binary, "--version"], env, "bw --version").strip()
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?", value):
        raise SecretError("bw --version returned an invalid version")
    return value


def status(binary, env):
    try:
        value = json.loads(_run([binary, "status"], env, "bw status"))
    except json.JSONDecodeError as error:
        raise SecretError("bw status returned invalid JSON") from error
    if not isinstance(value, dict) or value.get("status") not in ("unauthenticated", "locked", "unlocked"):
        raise SecretError("bw status returned an invalid status")
    server = value.get("serverUrl")
    if server is not None and not isinstance(server, str):
        raise SecretError("Run: bw config server <url>")
    if server:
        try:
            parsed = urlsplit(server)
            if (parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username is not None
                    or parsed.query or parsed.fragment or any(character.isspace() for character in server)):
                raise ValueError("Invalid server URL")
            parsed.port  # Validate the optional port without exposing its input in errors.
        except ValueError as error:
            raise SecretError("Run: bw config server <url>") from error
    return server, value["status"]
