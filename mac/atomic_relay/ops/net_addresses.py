"""Discover private IPv4 choices through an injected interface-text runner."""

from dataclasses import dataclass
import ipaddress
import re

TAILNET = "TAILNET"
LAN = "LAN"
_TAILNET = ipaddress.IPv4Network((0x64400000, 10))
_LAN = tuple(ipaddress.IPv4Network(value) for value in ((0x0A000000, 8), (0xAC100000, 12), (0xC0A80000, 16)))


@dataclass(frozen=True)
class Candidate:
    address: str
    kind: str


def parse_ifconfig(text: str) -> list[Candidate]:
    """Keep unique tailnet then LAN literals, retaining order within each group."""
    candidates = {}
    for match in re.finditer(r"^\s*inet\s+(\S+)", text, re.MULTILINE):
        try:
            address = ipaddress.IPv4Address(match[1])
        except ValueError as error:
            error.args = ("invalid IPv4 literal",)
            raise ValueError("component=ops operation=parse-ifconfig invalid IPv4 address") from error
        if address in _TAILNET:
            kind = TAILNET
        elif any(address in network for network in _LAN):
            kind = LAN
        else:
            continue
        candidates.setdefault(str(address), Candidate(str(address), kind))
    return sorted(candidates.values(), key=lambda item: item.kind != TAILNET)


def discover(runner) -> list[Candidate]:
    """Inspect interfaces once; the runner retains checked command failures."""
    return parse_ifconfig(runner.run(("/sbin/ifconfig", "-a"), timeout=10))
