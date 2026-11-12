"""Caller-approved relay setup and a pairing-code-bound wait."""

import logging
import secrets
import shlex

from atomic_relay.ops.cutover_plan import NEW_AGENT
from atomic_relay.ops.net_addresses import LAN, discover
from atomic_relay.pairing_cli import pair_output, phone_address

logger = logging.getLogger("atomic_relay")
ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


class PairingArgumentsError(ValueError):
    """An address choice is invalid, before any pairing registration."""


class PairProbe:
    """Adapt the existing checked request callback to the ops read-only probe."""
    def __init__(self, request):
        self.request = request

    def get(self, path, *, timeout=5):
        status, data = self.request(path, timeout=timeout)
        if status != 200:
            raise ValueError("server refused guided pairing check")
        return data


def _ensure_loaded(args, layout, runner, setup, ask, emit):
    loaded = runner.run(("launchctl", "print", layout.label(NEW_AGENT)), timeout=30, missing_service=True)
    if loaded is not None:
        return True
    if not args.yes and ask("Start the relay now? [y/N]").strip().lower() not in {"y", "yes"}:
        command = ("launchctl", "bootstrap", f"gui/{layout.uid}", str(layout.plist(NEW_AGENT)))
        emit("Relay start declined. Run: " + shlex.join(command))
        return False
    setup.ensure_loaded()
    return True


def _address(args, config, layout, runner, setup, ask, emit):
    configured = phone_address.configured_url(config)
    if configured is not None:
        return configured
    try:
        if args.address is not None:
            candidate = phone_address.explicit_candidate(args.address)
            if candidate.kind == LAN:
                emit(pair_output.LAN_WARNING)
        else:
            candidates = discover(runner)
            if not candidates:
                emit("No private address found; set ATOMIC_RELAY_PHONE_URL to your relay's address "
                     f"(for example wss://relay.example.com) in {args.env_file}.")
                return None
            pair_output.show_candidates(candidates, emit)
            candidate = phone_address.select_candidate(candidates, ask("Use which address? [1]"))
    except ValueError as error:
        raise PairingArgumentsError("invalid address selection") from error
    env = layout.service / "relay.env"
    question = f"Write ATOMIC_RELAY_HOST=127.0.0.1,{candidate.address} to {env} and restart the relay? [y/N]"
    if not args.yes and ask(question).strip().lower() not in {"y", "yes"}:
        emit("Relay address setup declined.")
        return None
    setup.apply(candidate.address)
    return f"ws://{candidate.address}:{config.port}"


def _connected_phone(data):
    devices = data.get("devices")
    if not isinstance(devices, list) or any(not isinstance(item, dict) for item in devices):
        raise ValueError("invalid device list")
    for device in devices:
        if device.get("device_form_factor") == "phone" and device.get("connected") is True:
            if not all(isinstance(device.get(key), str) and device[key] for key in ("device_name", "device_id")):
                raise ValueError("invalid phone identity")
            return device
    return None


def _wait(probe, code, registered_at, clock, sleep, emit):
    deadline = registered_at + 590
    while True:
        remaining = deadline - clock()
        if remaining <= 0:
            break
        status, check = probe.request("/pairing/check", {"code": code}, timeout=min(5, remaining))
        if status != 200 or check.get("ok") is not True or not isinstance(check.get("pending"), bool):
            raise ValueError("server refused pairing status or returned invalid JSON")
        remaining = deadline - clock()
        if remaining <= 0:
            break
        devices = probe.get("/bridge/devices", timeout=min(5, remaining))
        phone = _connected_phone(devices)
        if clock() < deadline and check["pending"] is False and phone is not None:
            pair_output.show_paired(phone, emit)
            logger.info("component=cli operation=pair paired device_id=%s", phone["device_id"])
            return 0
        remaining = deadline - clock()
        if remaining > 0:
            sleep(min(2, remaining))
    emit(pair_output.EXPIRED)
    return 3


def run_guided(args, config, *, layout, runner, setup, probe, ask, emit, clock, sleep, ttl_seconds, grants):
    if not _ensure_loaded(args, layout, runner, setup, ask, emit):
        return 2, None
    address = _address(args, config, layout, runner, setup, ask, emit)
    if address is None:
        return 2, None
    code = "".join(secrets.choice(ALPHABET) for _ in range(6))
    health_status, health = probe.request("/health", timeout=5)
    if health_status != 200 or health.get("status") != "ok":
        return 1, {"ok": False, "error": "server health check refused"}
    body = {"code": code, "ttl_seconds": ttl_seconds, "transport_hint": address.partition(":")[0]}
    if grants is not None:
        body["grants"] = grants
    registered_at = clock()
    status, result = probe.request("/pairing/register", body, timeout=5)
    if status != 200 or result.get("ok") is not True or result.get("code") != code:
        error = result.get("error", "server refused pairing")
        safe = error.replace(code, "[redacted]") if isinstance(error, str) else "server refused pairing"
        return 1, {"ok": False, "error": safe}
    logger.info("component=cli operation=pair registered")
    pair_output.show_pairing(address, code, emit, no_qr=args.no_qr, no_wait=args.no_wait)
    return (0 if args.no_wait else _wait(probe, code, registered_at, clock, sleep, emit)), None
