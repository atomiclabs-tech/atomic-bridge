"""Resolve websocket clients only through explicitly trusted transport peers."""

import ipaddress
import logging

logger = logging.getLogger("atomic_relay.web.address")


def _peer(request) -> str:
    transport = request.transport
    peer = transport.get_extra_info("peername") if transport is not None else None
    if not isinstance(peer, tuple) or not peer:
        return "unknown"
    try:
        return str(ipaddress.ip_address(peer[0]))
    except ValueError:
        logger.warning("component=address operation=peer invalid transport address")
        return "unknown"


def client_address(request, trusted_proxies) -> str:
    """Use the right-most untrusted hop only when the actual peer is trusted."""
    peer = _peer(request)
    if peer not in trusted_proxies or "X-Forwarded-For" not in request.headers:
        return peer
    try:
        values = request.headers["X-Forwarded-For"].split(",")
        if any("%" in value for value in values):
            raise ValueError("forwarded addresses cannot contain scope identifiers")
        hops = [str(ipaddress.ip_address(value.strip())) for value in values]
    except ValueError:
        logger.warning("component=address operation=resolve invalid forwarded address ignored peer=%s", peer)
        return peer
    return next((hop for hop in reversed(hops) if hop not in trusted_proxies), peer)


def transport_hint(request, trusted_proxies) -> str:
    """Accept forwarded TLS evidence only from an explicitly trusted peer."""
    forwarded_tls = _peer(request) in trusted_proxies and request.headers.get("X-Forwarded-Proto") == "https"
    return "wss" if request.secure or forwarded_tls else "ws"
