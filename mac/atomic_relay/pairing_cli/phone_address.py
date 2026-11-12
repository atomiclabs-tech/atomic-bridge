"""Pure address decisions for the guided pairing command."""

import ipaddress

from atomic_relay.config_network import parse_phone_url
from atomic_relay.ops.net_addresses import Candidate, LAN, TAILNET


def configured_url(config) -> str | None:
    if config.phone_url is not None:
        return config.phone_url
    for host in config.hosts:
        if not ipaddress.ip_address(host).is_loopback:
            authority = f"[{host}]" if ":" in host else host
            return f"ws://{authority}:{config.port}"
    return None


def explicit_candidate(value: str) -> Candidate:
    try:
        address = ipaddress.IPv4Address(value)
        if address.is_loopback or address.is_link_local:
            raise ValueError("address is not a usable private choice")
        parse_phone_url(f"ws://{address}")
    except ValueError as error:
        error.args = ("invalid private address",)
        raise ValueError("Choose a private IPv4 address.") from error
    kind = TAILNET if address in ipaddress.IPv4Network((0x64400000, 10)) else LAN
    return Candidate(str(address), kind)


def select_candidate(candidates, answer: str) -> Candidate:
    selection = answer.strip() or "1"
    if not selection.isascii() or not selection.isdecimal() or len(selection) > 6:
        raise ValueError("Choose a listed address number.")
    index = int(selection) - 1
    if not 0 <= index < len(candidates):
        raise ValueError("Choose a listed address number.")
    return candidates[index]
