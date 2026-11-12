# Ported from plugin/relay/channels/bridge.py at upstream commit da782a8.
"""Device aliases and public cached status, independent of the transport."""

import re
from dataclasses import dataclass, field
from typing import Protocol


class Socket(Protocol):
    closed: bool

    async def send_str(self, data: str) -> None: ...


def normalize_selector(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", value.strip().lower())


def derive_aliases(device_id, name, surface, form_factor):
    aliases = {device_id, normalize_selector(device_id), name, normalize_selector(name)}
    compact = normalize_selector(" ".join((device_id, name, surface, form_factor)))
    if any(word in compact for word in ("notemax", "boox", "onyx")):
        aliases.update({"boox", "notemax", "note", "tablet"})
    if "pixel" in compact or "fold" in compact:
        aliases.update({"phone", "fold", "pixel"})
    if form_factor.lower() == "phone":
        aliases.add("phone")
    if form_factor.lower() in {"tablet", "eink", "boox"}:
        aliases.add("tablet")
    return aliases - {""}


@dataclass
class BridgeClient:
    device_id: str
    device_name: str
    socket: Socket = field(repr=False)
    last_seen_at: float
    latest_status: dict = field(repr=False)
    session_token_prefix: str = field(repr=False)
    client_surface: str
    device_form_factor: str
    aliases: set[str]

    @property
    def connected(self):
        return not self.socket.closed

    def status_payload(self, now, *, active=False):
        payload = dict(self.latest_status)
        # A supplied device dictionary is phone-owned protocol data.
        payload.setdefault("device", {})
        payload.setdefault("device_id", self.device_id)
        payload.setdefault("device_name", self.device_name)
        payload.update(
            phone_connected=self.connected, connected=self.connected, active=active,
            last_seen_seconds_ago=max(0, int(now - self.last_seen_at)),
            aliases=sorted(self.aliases), session_token_prefix=self.session_token_prefix,
            client_surface=self.client_surface, device_form_factor=self.device_form_factor,
        )
        return payload
