"""Network parsers stay pure and never expose a configured address in errors."""

import traceback

import pytest

from atomic_relay.config_network import parse_hosts, parse_phone_url


def test_host_literals_normalize_and_keep_order():
    assert parse_hosts("127.0.0.1,2001:0db8::1,::1,127.0.0.1") == ("127.0.0.1", "2001:db8::1", "::1")


@pytest.mark.parametrize("value", ["0.0.0.0", "::", "127.0.0.1,::ffff:0.0.0.0", "127.0.0.1,invalid-private-host"])
def test_host_failures_preserve_a_redacted_cause(value):
    with pytest.raises(ValueError) as error:
        parse_hosts(value)
    trace = "".join(traceback.format_exception(error.value))
    assert value not in trace


@pytest.mark.parametrize("value,expected", [
    ("ws://[fe80::1]", "ws://[fe80::1]"),
    ("http://10.1.2.3:80", "ws://10.1.2.3:80"),
    ("wss://[2001:0db8::1]:443", "wss://[2001:db8::1]:443"),
    (" https://Relay.Example.com ", "wss://relay.example.com"),
])
def test_phone_url_pure_normalization(value, expected):
    assert parse_phone_url(value) == expected


@pytest.mark.parametrize("value", ["wss://private.example:not-a-port", "wss://[private-invalid]", "ws://198.51.100.1",
                                 "wss://[::1]invalid", "wss://[::1]invalid:443"])
def test_phone_url_errors_never_include_the_url_in_the_chain(value):
    with pytest.raises(ValueError) as error:
        parse_phone_url(value)
    trace = "".join(traceback.format_exception(error.value))
    assert value not in trace
