"""Pure address precedence, manual literals and numbered choices."""

from types import SimpleNamespace

import pytest

from atomic_relay.ops.net_addresses import Candidate, LAN, TAILNET
from atomic_relay.pairing_cli.phone_address import configured_url, explicit_candidate, select_candidate


@pytest.mark.parametrize("phone_url,hosts,expected", [
    ("wss://relay.example.com", ("127.0.0.1", "10.1.2.3"), "wss://relay.example.com"),
    (None, ("::1", "127.0.0.1", "100.64.1.2", "10.1.2.3"), "ws://100.64.1.2:8767"),
    (None, ("::1", "fd00::1"), "ws://[fd00::1]:8767"),
    (None, ("127.0.0.1", "::1"), None),
])
def test_configured_address_precedence(phone_url, hosts, expected):
    config = SimpleNamespace(phone_url=phone_url, hosts=hosts, port=8767)
    assert configured_url(config) == expected


@pytest.mark.parametrize("value,kind", [("100.64.1.2", TAILNET), ("192.168.1.5", LAN), ("10.1.2.3", LAN)])
def test_explicit_private_choice_need_not_be_discovered(value, kind):
    assert explicit_candidate(value) == Candidate(value, kind)


@pytest.mark.parametrize("value", ["relay.local", "8.8.8.8", "127.0.0.1", "169.254.1.2", "::1", "0.0.0.0"])
def test_unsafe_explicit_choice_has_redacted_failure(value):
    with pytest.raises(ValueError) as caught:
        explicit_candidate(value)
    private_absent = value not in str(caught.value) + str(caught.value.__cause__)
    assert private_absent


def test_numbered_choice_defaults_to_first_and_checks_range():
    candidates = [Candidate("100.64.1.2", TAILNET), Candidate("10.1.2.3", LAN)]
    assert select_candidate(candidates, "") == candidates[0]
    assert select_candidate(candidates, " 2 ") == candidates[1]
    for answer in ("0", "3", "-1", "bad", "999999999999"):
        with pytest.raises(ValueError):
            select_candidate(candidates, answer)
