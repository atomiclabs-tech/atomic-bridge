from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from atomic_relay.web.address import client_address, transport_hint


def request(peer="127.0.0.1", *, headers=None, secure=False):
    transport = Mock()
    transport.get_extra_info.return_value = (peer, 1) if peer is not None else None
    return SimpleNamespace(transport=transport, headers=headers or {}, secure=secure)


@pytest.mark.parametrize("forwarded", ["198.51.100.4", "malformed-private-header", "198.51.100.4, 127.0.0.1"])
def test_untrusted_peers_ignore_forwarded_address_and_tls(forwarded, caplog):
    incoming = request(headers={"X-Forwarded-For": forwarded, "X-Forwarded-Proto": "https"})
    assert client_address(incoming, frozenset()) == "127.0.0.1"
    assert transport_hint(incoming, frozenset()) == "ws"
    assert forwarded not in caplog.text


@pytest.mark.parametrize("chain,expected", [
    ("203.0.113.9, 198.51.100.8, 192.0.2.2", "198.51.100.8"),
    ("192.0.2.2, 127.0.0.1", "127.0.0.1"),
    ("198.51.100.8", "198.51.100.8"),
    ("2001:0db8::8, ::1, 192.0.2.2", "2001:db8::8"),
])
def test_trusted_peer_uses_rightmost_untrusted_hop(chain, expected):
    proxies = frozenset({"127.0.0.1", "192.0.2.2", "::1"})
    incoming = request(headers={"X-Forwarded-For": chain, "X-Forwarded-Proto": "https"})
    assert client_address(incoming, proxies) == expected
    assert transport_hint(incoming, proxies) == "wss"


@pytest.mark.parametrize("forwarded", ["", "private-malformed-header", "198.51.100.8,",
                                      "198.51.100.8, private-malformed-header", "fe80::1%private-malformed-header"])
def test_invalid_trusted_forwarding_is_ignored_without_logging_header(forwarded, caplog):
    incoming = request(headers={"X-Forwarded-For": forwarded})
    assert client_address(incoming, {"127.0.0.1"}) == "127.0.0.1"
    assert "invalid forwarded address ignored peer=127.0.0.1" in caplog.text
    assert "private-malformed-header" not in caplog.text
    assert "198.51.100.8" not in caplog.text


def test_missing_peer_header_and_direct_tls_are_safe(caplog):
    incoming = request(peer=None, headers={"X-Forwarded-For": "private-header", "X-Forwarded-Proto": "https"})
    assert client_address(incoming, {"127.0.0.1"}) == "unknown"
    assert transport_hint(incoming, {"127.0.0.1"}) == "ws"
    assert client_address(request(peer="private-invalid-peer"), set()) == "unknown"
    assert "private-invalid-peer" not in caplog.text
    assert client_address(request(peer="::1"), {"::1"}) == "::1"
    assert transport_hint(request(secure=True), set()) == "wss"
