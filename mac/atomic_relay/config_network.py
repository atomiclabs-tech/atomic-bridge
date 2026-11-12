"""Pure parsing for explicit relay listeners and a phone-facing endpoint."""

import ipaddress
import re
from urllib.parse import urlsplit

_CLEAR_V4 = tuple(ipaddress.IPv4Network(value) for value in (
    (0x0A000000, 8), (0xAC100000, 12), (0xC0A80000, 16), (0x7F000000, 8), (0x64400000, 10), (0xA9FE0000, 16),
))
_CLEAR_V6 = tuple(ipaddress.ip_network(value) for value in ("fc00::/7", "fe80::/10", "::1/128"))


def parse_hosts(value: str) -> tuple[str, ...]:
    """Keep canonical literal addresses in order, requiring loopback and no wildcard."""
    addresses = []
    for item in value.split(","):
        try:
            address = ipaddress.ip_address(item.strip())
        except ValueError as error:
            error.args = ("invalid IP literal",)
            raise ValueError("component=config operation=parse ATOMIC_RELAY_HOST requires literal IP addresses") from error
        mapped = getattr(address, "ipv4_mapped", None)
        if address.is_unspecified or mapped is not None and mapped.is_unspecified:
            raise ValueError("component=config operation=parse ATOMIC_RELAY_HOST refuses wildcard addresses")
        addresses.append(address)
    if not any(address.is_loopback for address in addresses):
        raise ValueError("component=config operation=parse ATOMIC_RELAY_HOST requires a loopback address")
    return tuple(dict.fromkeys(str(address) for address in addresses))


def _phone_host(host: str, encrypted: bool) -> str:
    try:
        address = ipaddress.ip_address(host)
    except ValueError as error:
        error.args = ("invalid IP literal",)
        labels = host.split(".")
        if not encrypted or any(not label or label.startswith("-") or label.endswith("-")
                                or not all(char.isalnum() or char == "-" for char in label) for label in labels):
            raise ValueError("component=config operation=parse ATOMIC_RELAY_PHONE_URL requires a valid host") from error
        return host.lower()
    if not encrypted and not any(address in network for network in (_CLEAR_V4 if address.version == 4 else _CLEAR_V6)):
        raise ValueError("component=config operation=parse ATOMIC_RELAY_PHONE_URL requires a private literal for cleartext")
    return f"[{address}]" if address.version == 6 else str(address)


def parse_phone_url(value: str) -> str | None:
    """Normalize an optional authority-only URL; cleartext requires a private literal."""
    if any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise ValueError("component=config operation=parse ATOMIC_RELAY_PHONE_URL refuses control characters")
    if not value.strip():
        return None
    value = value.strip()
    match = re.fullmatch(r"(ws|wss|http|https)://([^/?#]+)", value, re.IGNORECASE)
    if match is None or any(char.isspace() or char in "@\\%" for char in match[2]) or match[2].endswith(":"):
        raise ValueError("component=config operation=parse ATOMIC_RELAY_PHONE_URL requires a credential-free authority only")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError as error:
        error.args = ("invalid URL authority",)
        raise ValueError("component=config operation=parse ATOMIC_RELAY_PHONE_URL has an invalid authority") from error
    if not parsed.hostname or port is not None and not 1 <= port <= 65535:
        raise ValueError("component=config operation=parse ATOMIC_RELAY_PHONE_URL requires a host and valid optional port")
    encrypted = parsed.scheme in {"wss", "https"}
    host = _phone_host(parsed.hostname, encrypted)
    return f"{'wss' if encrypted else 'ws'}://{host}" + (f":{port}" if port is not None else "")
