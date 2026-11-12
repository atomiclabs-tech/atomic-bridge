"""Read only the non-secret relay settings needed for local operator checks."""

import ipaddress
import shlex
import tomllib
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

from atomic_relay.ops.manifest import read_manifest


def assignments(path):
    result = {}
    allowed = {"ATOMIC_RELAY_PORT", "ATOMIC_RELAY_SERVER_VERSION", "ANDROID_BRIDGE_URL",
               "ATOMIC_RELAY_ADMIN_SECRET_FILE"}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        key, separator, value = raw.partition("=")
        if not separator:
            raise ValueError(f"invalid relay.env assignment at line {number}")
        if key.strip() in allowed:
            parts = shlex.split(value, comments=True)
            if len(parts) > 1 or (not parts and key.strip() != "ATOMIC_RELAY_ADMIN_SECRET_FILE"):
                raise ValueError(f"invalid operator setting at line {number}")
            result[key.strip()] = parts[0] if parts else ""
    return result


@dataclass(frozen=True)
class Settings:
    url: str
    new_version: str
    old_version: str
    secret_file: Path | None = None

    def version(self, rollback):
        return self.old_version if rollback else self.new_version


def load_settings(layout, defaults, *, upgrade=False, source=None):
    from_snapshot = source is not None
    source = layout.service if source is None else source
    configured = assignments(source / "relay.env")
    settings = {**assignments(defaults), **configured}
    parsed = urlsplit(settings["ANDROID_BRIDGE_URL"])
    if (parsed.scheme != "http" or not parsed.hostname or not ipaddress.ip_address(parsed.hostname).is_loopback
            or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}):
        raise ValueError("operator checks require a credential-free HTTP loopback URL")
    port = int(settings["ATOMIC_RELAY_PORT"])
    if not 1 <= port <= 65535:
        raise ValueError("operator relay port must be between 1 and 65535")
    host = f"[{parsed.hostname}]" if ":" in parsed.hostname else parsed.hostname
    url = urlunsplit((parsed.scheme, f"{host}:{port}", "", "", ""))
    package = tomllib.loads((source / "pyproject.toml").read_text(encoding="utf-8"))
    new_version = configured.get("ATOMIC_RELAY_SERVER_VERSION", package["project"]["version"])
    if upgrade:
        new_version = assignments(defaults)["ATOMIC_RELAY_SERVER_VERSION"]
        old_version = package["project"]["version"]
    else:
        old = read_manifest(layout.plugin("hermes-relay"))
        if old.name != "hermes-relay":
            raise ValueError("installed relay version identity is invalid")
        old_version = old.version
    if not new_version or not old_version:
        raise ValueError("installed relay version identity is invalid")
    # A restored relay must not inherit the current install's credential default.
    raw = (configured if from_snapshot else settings).get("ATOMIC_RELAY_ADMIN_SECRET_FILE", "")
    if raw.startswith("~") and not raw.startswith("~/"):
        raise ValueError("operator secret path must be absolute or home-relative")
    secret = layout.home / raw[2:] if raw.startswith("~/") else Path(raw) if raw else None
    if secret is not None and not secret.is_absolute():
        raise ValueError("operator secret path must be absolute or home-relative")
    return Settings(url, new_version, old_version, secret)
