"""Pairing material goes only to the caller's stdout emitter."""

from urllib.parse import quote, urlencode

from atomic_relay.ops.net_addresses import LAN, TAILNET
from atomic_relay.qr import encode, render_terminal

LAN_WARNING = "Traffic on this network is not encrypted; anyone on it can see it."
EXPIRED = "No phone paired within 10 minutes; the code has expired. Run the pair command again."


def show_candidates(candidates, emit):
    for index, candidate in enumerate(candidates, 1):
        label = "recommended: tailnet, encrypted by WireGuard" if candidate.kind == TAILNET else "LAN"
        emit(f"{index}. {candidate.address} ({label})")
    if any(candidate.kind == LAN for candidate in candidates):
        emit(LAN_WARNING)


def show_pairing(address, code, emit, *, no_qr=False, no_wait=False):
    link = "atomic://pair?" + urlencode({"relay": address, "code": code}, quote_via=quote)
    qr = None if no_qr else render_terminal(encode(link))
    emit(f"Relay address for the phone: {address}")
    emit(f"Pairing code: {code}  (single use, expires in 10 minutes)")
    emit("On the phone: open Atomic Bridge, enter the address and the code, tap Pair.")
    emit(f"Or open this link on the phone: {link}")
    if qr is not None:
        emit("Or point the phone camera at this code and tap Open:")
        emit(qr)
    if not no_wait:
        emit("Waiting for the phone (up to 10 minutes; Ctrl-C to stop waiting)...")


def show_paired(device, emit):
    emit(f"Paired: {device['device_name']} (device id {device['device_id']})")
