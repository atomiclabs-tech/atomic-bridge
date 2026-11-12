"""Synthetic pairing material remains inside an injected stdout capture."""

import logging
import secrets
from urllib.parse import parse_qs, urlsplit

import pytest

from atomic_relay.ops.net_addresses import Candidate, LAN, TAILNET
from atomic_relay.pairing_cli import pair_output
from atomic_relay.qr import encode


def test_pairing_block_and_real_qr_use_the_same_deep_link_without_logging(monkeypatch, caplog):
    code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(6))
    address, output, encoded = "wss://relay.example.com", [], []
    def record(text):
        encoded.append(text)
        return encode(text)
    monkeypatch.setattr(pair_output, "encode", record)
    with caplog.at_level(logging.DEBUG):
        pair_output.show_pairing(address, code, output.append)
    named = "On the phone: open Atomic Bridge, enter the address and the code, tap Pair." in output
    assert named
    query = parse_qs(urlsplit(encoded[0]).query)
    matched = query == {"relay": [address], "code": [code]}
    assert matched
    visible = code in "\n".join(output) and encoded[0] in "\n".join(output)
    assert visible
    leaked = any(value in caplog.text for value in (address, code, encoded[0]))
    assert not leaked
    assert len(encoded) == 1 and any("█" in line for line in output)
    assert output[-1].startswith("Waiting for the phone")


def test_no_qr_and_no_wait_keep_the_link_but_skip_qr_and_waiting(monkeypatch):
    def forbidden(*args):
        raise AssertionError("unexpected QR encode")
    monkeypatch.setattr(pair_output, "encode", forbidden)
    code = secrets.token_hex(3).upper()
    output = []
    pair_output.show_pairing("ws://10.1.2.3:8767", code, output.append, no_qr=True, no_wait=True)
    visible = any(line.startswith("Or open this link") for line in output)
    assert visible
    assert not any("Waiting" in line or "camera" in line for line in output)


def test_qr_failure_is_loud_before_any_pairing_block_is_emitted(monkeypatch):
    def fail(text):
        raise ValueError("synthetic QR capacity failure")
    monkeypatch.setattr(pair_output, "encode", fail)
    output = []
    with pytest.raises(ValueError, match="synthetic QR capacity"):
        pair_output.show_pairing("wss://relay.example.com", secrets.token_hex(3).upper(), output.append)
    assert not output


def test_candidate_list_marks_tailnet_and_warns_once_for_lan():
    output = []
    pair_output.show_candidates([Candidate("100.64.1.2", TAILNET), Candidate("10.1.2.3", LAN),
                                 Candidate("192.168.1.5", LAN)], output.append)
    assert "recommended: tailnet, encrypted by WireGuard" in output[0]
    assert output.count(pair_output.LAN_WARNING) == 1
