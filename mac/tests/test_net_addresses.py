"""Address discovery uses fixture text and a fake command runner only."""

import subprocess

import pytest

from atomic_relay.ops.commands import CommandRunner, StepFailure
from atomic_relay.ops.net_addresses import Candidate, LAN, TAILNET, discover, parse_ifconfig

IFCONFIG = """lo0: flags=8049<UP,LOOPBACK,RUNNING,MULTICAST>
    inet 127.0.0.1 netmask 0xff000000
en0: flags=8863<UP,BROADCAST,SMART,RUNNING,SIMPLEX,MULTICAST>
    inet 192.168.1.5 netmask 0xffffff00 broadcast 192.168.1.255
    inet6 fe80::1%en0 prefixlen 64
awdl0: flags=8943<UP,BROADCAST,RUNNING,PROMISC,SIMPLEX,MULTICAST>
    inet 169.254.1.2 netmask 0xffff0000
utun4: flags=8051<UP,POINTOPOINT,RUNNING,MULTICAST>
    inet 100.64.1.2 --> 100.64.1.2 netmask 0xffffffff
en1: flags=8863<UP,BROADCAST,SMART,RUNNING,SIMPLEX,MULTICAST>
    inet 203.0.113.1 netmask 0xffffff00
    inet 192.168.1.5 netmask 0xffffff00
"""


def test_fixture_filters_and_deduplicates_with_tailnet_first():
    assert parse_ifconfig(IFCONFIG) == [Candidate("100.64.1.2", TAILNET), Candidate("192.168.1.5", LAN)]
    assert parse_ifconfig("inet6 ::1 prefixlen 128\nstatus: inactive\n") == []


@pytest.mark.parametrize("address,kind", [
    ("10.1.2.3", LAN), ("172.16.1.2", LAN), ("172.31.1.2", LAN), ("192.168.1.2", LAN),
    ("100.64.1.2", TAILNET), ("100.127.1.2", TAILNET), ("100.63.1.2", None),
    ("100.128.1.2", None), ("172.15.1.2", None), ("172.32.1.2", None), ("192.0.2.1", None),
])
def test_only_named_tailnet_and_lan_ranges_are_candidates(address, kind):
    assert parse_ifconfig(f"\tinet {address} netmask 0xffffffff\n") == (
        [Candidate(address, kind)] if kind else [])


def test_tailnet_and_lan_each_keep_interface_order():
    text = "\n".join(f"inet {address}" for address in ("10.1.2.3", "100.65.1.2", "172.16.1.2", "100.64.1.2"))
    assert [item.address for item in parse_ifconfig(text)] == ["100.65.1.2", "100.64.1.2", "10.1.2.3", "172.16.1.2"]


def test_malformed_ipv4_fails_without_exposing_the_input():
    value = "invalid-private-interface-address"
    with pytest.raises(ValueError, match="parse-ifconfig") as caught:
        parse_ifconfig("inet " + value)
    assert value not in str(caught.value) + str(caught.value.__cause__)


def test_discover_uses_checked_command_with_ten_second_timeout(monkeypatch):
    calls = []
    def execute(argv, **options):
        calls.append((argv, options))
        return subprocess.CompletedProcess(argv, 0, IFCONFIG, "")
    monkeypatch.setattr(subprocess, "run", execute)
    assert discover(CommandRunner()) == parse_ifconfig(IFCONFIG)
    assert calls == [(("/sbin/ifconfig", "-a"), {"check": True, "timeout": 10, "capture_output": True, "text": True})]


def test_ifconfig_failure_keeps_command_and_exit_code(monkeypatch, caplog):
    def execute(argv, **options):
        raise subprocess.CalledProcessError(7, argv, stderr="synthetic interface inspection failure")
    monkeypatch.setattr(subprocess, "run", execute)
    with pytest.raises(StepFailure) as caught:
        discover(CommandRunner())
    assert caught.value.argv == ("/sbin/ifconfig", "-a") and caught.value.code == 7
    assert "/sbin/ifconfig -a" in str(caught.value) and "exit code: 7" in str(caught.value)
    assert any(record.exc_info for record in caplog.records)
