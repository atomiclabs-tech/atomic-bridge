"""Fake runner, request callback and virtual time; no live pairing or stdout."""
# no_parallel: isolated operator fixture and injected command interfaces

import logging
from types import SimpleNamespace

import pytest

from atomic_relay.ops.layout import Layout
from atomic_relay.pairing_cli.pair_guided import ALPHABET, PairProbe, run_guided
from atomic_relay.pairing_cli.pair_output import EXPIRED
from ops_cases import FakeClock


class Runner:
    def __init__(self):
        self.loaded, self.calls, self.interfaces = True, [], "inet 10.1.2.3\ninet 100.64.1.2\n"

    def run(self, argv, *, timeout=30, missing_service=False):
        self.calls.append((argv, timeout, missing_service))
        if argv[0] == "/sbin/ifconfig":
            return self.interfaces
        return "loaded" if self.loaded else None


class Setup:
    def __init__(self):
        self.calls = []

    def ensure_loaded(self):
        self.calls.append("start")

    def apply(self, address):
        self.calls.append("apply")


class Requests:
    def __init__(self, clock):
        self.clock, self.calls, self.pending, self.index, self.code = clock, [], [True, False], 0, None
        self.cost, self.refuse, self.connected, self.kind = 0, False, True, "phone"
        self.health_status, self.health_state = 200, "ok"

    def __call__(self, path, body=None, *, timeout=5):
        self.calls.append((path, body, timeout, self.clock.now))
        self.clock.now += min(timeout, self.cost)
        if path == "/health":
            return self.health_status, {"status": self.health_state}
        if path == "/pairing/register":
            self.code = body["code"]
            return (401, {"error": "refused " + self.code}) if self.refuse else (200, {"ok": True, "code": self.code})
        if path == "/pairing/check":
            pending = self.pending[min(self.index, len(self.pending) - 1)]
            self.index += 1
            return 200, {"ok": True, "pending": pending}
        return 200, {"devices": [{"connected": self.connected, "device_form_factor": self.kind,
                                  "device_name": "Test phone", "device_id": "synthetic-phone-id",
                                  "session_token_prefix": str(self.index)}]}


@pytest.fixture
def guided(tmp_path):
    args = SimpleNamespace(yes=False, address=None, no_wait=False, no_qr=True, env_file=tmp_path / "relay.env")
    args.env_file.write_bytes(b"# unchanged private fixture\n")
    config = SimpleNamespace(phone_url="wss://relay.example.com", hosts=("127.0.0.1",), port=8767)
    clock, runner, setup, output, questions = FakeClock(), Runner(), Setup(), [], []
    requests = Requests(clock)
    answers = []
    def ask(question):
        questions.append(question)
        return answers.pop(0)
    def run():
        return run_guided(args, config, layout=Layout(tmp_path, 42), runner=runner, setup=setup,
                          probe=PairProbe(requests), ask=ask, emit=output.append, clock=clock.clock,
                          sleep=clock.sleep, ttl_seconds=30 * 86400, grants={"bridge": 0})
    return SimpleNamespace(args=args, config=config, clock=clock, runner=runner, setup=setup,
                           requests=requests, output=output, questions=questions, answers=answers, run=run)


def test_registered_code_and_wait_are_tied_to_consumption_and_connected_phone(guided, caplog):
    with caplog.at_level(logging.INFO, logger="atomic_relay"):
        result = guided.run()
    assert result == (0, None) and guided.clock.sleeps == [2]
    registration = next(body for path, body, _, _ in guided.requests.calls if path == "/pairing/register")
    valid = len(registration["code"]) == 6 and set(registration["code"]) <= set(ALPHABET)
    assert valid
    matched = all(body["code"] == registration["code"] for path, body, _, _ in guided.requests.calls if path == "/pairing/check")
    assert matched
    assert registration["transport_hint"] == "wss" and registration["ttl_seconds"] == 30 * 86400
    assert registration["grants"] == {"bridge": 0}
    paired = guided.output[-1] == "Paired: Test phone (device id synthetic-phone-id)"
    assert paired
    leaked = any(value in caplog.text for value in (registration["code"], guided.config.phone_url, "atomic://pair?"))
    assert not leaked
    assert not guided.setup.calls and not guided.questions


def test_refresh_with_new_prefix_and_connected_phone_does_not_finish_while_pending(guided):
    guided.requests.pending = [True, True, False]
    assert guided.run() == (0, None)
    assert guided.clock.sleeps == [2, 2] and guided.requests.index == 3


@pytest.mark.parametrize("kind,connected", [("phone", False), ("desktop", True)])
def test_consumed_code_without_connected_phone_never_reports_pairing(guided, kind, connected):
    guided.requests.pending, guided.requests.kind, guided.requests.connected = [False], kind, connected
    assert guided.run() == (3, None)
    assert guided.clock.now == 590 and guided.output[-1] == EXPIRED


def test_pending_code_expires_at_590_seconds_without_false_pairing(guided):
    guided.requests.pending = [True]
    assert guided.run() == (3, None)
    assert sum(guided.clock.sleeps) == 590 and guided.output[-1] == EXPIRED
    assert guided.requests.index == 295


def test_network_calls_are_clipped_to_the_remaining_deadline(guided):
    guided.requests.pending, guided.requests.cost = [True], 3
    assert guided.run() == (3, None)
    registered_at = next(start for path, _, _, start in guided.requests.calls if path == "/pairing/register")
    final = [(timeout, start) for path, _, timeout, start in guided.requests.calls if path in {"/pairing/check", "/bridge/devices"}]
    assert all(start + timeout <= registered_at + 590 for timeout, start in final)
    assert guided.clock.now == registered_at + 590


def test_no_wait_never_polls_pairing_or_devices(guided):
    guided.args.no_wait = True
    assert guided.run() == (0, None)
    assert [path for path, _, _, _ in guided.requests.calls] == ["/health", "/pairing/register"]
    assert not guided.clock.sleeps


def test_explicit_address_with_yes_skips_all_prompts_and_discovery(guided):
    guided.config.phone_url = None
    guided.args.address, guided.args.yes, guided.args.no_wait = "100.64.1.2", True, True
    assert guided.run() == (0, None)
    assert guided.setup.calls == ["apply"] and not guided.questions
    assert all(argv[0] != "/sbin/ifconfig" for argv, _, _ in guided.runner.calls)


def test_actual_declined_confirmation_preserves_env_and_never_calls_setup(guided):
    guided.config.phone_url = None
    before = guided.args.env_file.read_bytes()
    guided.answers[:] = ["", ""]
    assert guided.run() == (2, None)
    assert guided.args.env_file.read_bytes() == before and not guided.setup.calls and not guided.requests.calls
    assert guided.questions[0] == "Use which address? [1]" and guided.questions[-1].endswith("[y/N]")


def test_no_candidates_prints_configuration_guidance_without_prompt(guided):
    guided.config.phone_url, guided.runner.interfaces = None, ""
    assert guided.run() == (2, None)
    assert "No private address found; set ATOMIC_RELAY_PHONE_URL" in guided.output[-1]
    assert not guided.questions and not guided.setup.calls


def test_missing_service_decline_never_calls_ensure_loaded(guided):
    guided.runner.loaded = False
    guided.answers[:] = [""]
    assert guided.run() == (2, None)
    assert guided.questions == ["Start the relay now? [y/N]"]
    assert not guided.setup.calls and not guided.requests.calls
    assert "launchctl bootstrap" in guided.output[-1]


def test_missing_service_yes_obtains_start_before_registration(guided):
    guided.runner.loaded, guided.args.yes, guided.args.no_wait = False, True, True
    assert guided.run() == (0, None)
    assert guided.setup.calls == ["start"] and not guided.questions


def test_registration_refusal_redacts_code_before_error_result(guided):
    guided.requests.refuse = True
    exit_code, result = guided.run()
    assert exit_code == 1 and not guided.output
    leaked = guided.requests.code in result["error"]
    assert not leaked


@pytest.mark.parametrize("status,state", [(503, "ok"), (200, "unavailable")])
def test_health_refusal_retains_explicit_mode_error_and_never_registers(guided, status, state):
    guided.requests.health_status, guided.requests.health_state = status, state
    assert guided.run() == (1, {"ok": False, "error": "server health check refused"})
    assert [path for path, _, _, _ in guided.requests.calls] == ["/health"] and not guided.output
