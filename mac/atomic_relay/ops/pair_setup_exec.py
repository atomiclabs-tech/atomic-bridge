"""Apply an already-confirmed relay setup through bounded injected ports."""

from datetime import datetime, timezone
import logging

from atomic_relay.ops import envfile
from atomic_relay.ops.commands import StepFailure
from atomic_relay.ops.cutover_plan import NEW_AGENT
from atomic_relay.ops.pair_setup_plan import build_plan
from atomic_relay.ops.probe import wait_for

logger = logging.getLogger("atomic_relay.ops")


def _utc_now():
    return datetime.now(timezone.utc)


class PairSetupError(RuntimeError):
    """A setup failure retains its cause and identifies the available backup."""


class PairSetup:
    def __init__(self, layout, runner, probe, clock, sleep, emit, *, now=_utc_now):
        """The caller obtains approval before invoking either action method."""
        self.layout, self.runner, self.probe = layout, runner, probe
        self.clock, self.sleep, self.emit, self.now = clock, sleep, emit, now
        self.backup = None

    def _preflight(self):
        if self.layout.service.is_symlink():
            raise ValueError("relay service directory must not be a symlink")
        for path in (self.layout.service / "relay.env", self.layout.plist(NEW_AGENT)):
            if path.is_symlink() or not path.is_file():
                raise ValueError("relay env and plist must be regular non-symlink files")
        self.runner.require(("launchctl",))

    def _loaded(self):
        return self.runner.run(("launchctl", "print", self.layout.label(NEW_AGENT)),
                               timeout=30, missing_service=True) is not None

    def _bootstrap(self):
        self.runner.run(("launchctl", "bootstrap", f"gui/{self.layout.uid}", str(self.layout.plist(NEW_AGENT))),
                        timeout=30)

    def _restart(self):
        if self._loaded():
            result = self.runner.run(("launchctl", "bootout", self.layout.label(NEW_AGENT)),
                                     timeout=30, missing_service=True)
            if result is None:
                logger.warning("component=ops operation=pair-setup relay already unloaded label=%s", NEW_AGENT)
        self._bootstrap()

    def _health(self, budget=60):
        wait_for(self.probe, "/health", lambda data: data.get("status") == "ok",
                 budget=budget, clock=self.clock, sleep=self.sleep)

    def _fail(self, operation, error):
        backup = str(self.backup) if self.backup is not None else "not created"
        logger.exception("component=ops operation=pair-setup failed step=%s backup=%s", operation, backup)
        raise PairSetupError(f"Relay setup failed during {operation}; backup: {backup}") from error

    def ensure_loaded(self) -> bool:
        """After caller approval, start an absent relay and poll health; return whether started."""
        try:
            self._preflight()
            if self._loaded():
                return False
            self._bootstrap()
            self._health()
            return True
        except (OSError, ValueError, StepFailure) as error:
            self._fail("start", error)

    def _backup(self, path, data):
        stamp = self.now().astimezone(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
        backup = path.with_name(path.name + ".bak-" + stamp)
        if backup.exists() or backup.is_symlink():
            raise FileExistsError("relay environment backup already exists")
        envfile.atomic_write(backup, data)
        self.backup = backup
        self.emit(f"Backup: {backup}")

    def apply(self, address):
        """After caller approval, back up, edit, restart and check; unchanged env is not rewritten."""
        operation = "plan"
        try:
            steps = build_plan(self.layout, address)
            operation = "preflight"
            self._preflight()
            path = self.layout.service / "relay.env"
            data, current = path.read_bytes(), None
            for line in data.decode("utf-8").splitlines():
                key, separator, value = line.partition("=")
                if separator and key.strip() == "ATOMIC_RELAY_HOST":
                    current = value.strip()
            if current and len(current) >= 2 and current[0] in "'\"" and current[-1] == current[0]:
                current = current[1:-1]
            desired = f"127.0.0.1,{address}"
            if current == desired:
                operation = "restart"
                self._restart()
                operation = "health"
                self._health(steps[-1].timeout)
                return None
            operation = "backup"
            self._backup(path, data)
            operation = "env"
            envfile.update(path, {"ATOMIC_RELAY_HOST": desired})
            operation = "restart"
            self._restart()
            operation = "health"
            self._health(steps[-1].timeout)
            return self.backup
        except (OSError, ValueError, StepFailure) as error:
            self._fail(operation, error)
