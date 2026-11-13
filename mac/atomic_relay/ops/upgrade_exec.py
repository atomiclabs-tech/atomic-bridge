"""Execute and reverse one journalled upgrade with injected commands and probes."""

from datetime import datetime, timezone
import hashlib
import json
import logging
from pathlib import Path
import shutil
import tempfile
import time
import tomllib

from atomic_relay.config_network import parse_hosts
from atomic_relay.ops.cutover_plan import NEW_AGENT, NEW_PLUGIN, RESTART
from atomic_relay.ops.envfile import atomic_write, update
from atomic_relay.ops.install_files import package_files, sync_package
from atomic_relay.ops.manifest import read_manifest
from atomic_relay.ops.probe import healthy, phone_connected, wait_for
from atomic_relay.ops.secret import create_if_missing
from atomic_relay.ops.upgrade_plan import build_plan

logger = logging.getLogger("atomic_relay.ops")


def timestamp():
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")


def _inventory(directory):
    files = {}
    for name in ("atomic_relay", NEW_PLUGIN):
        if (directory / name).is_symlink():
            raise ValueError("backup package must not be a symlink")
        files.update({f"{name}/{path}": data for path, data in package_files(directory / name).items()})
    for name in ("pyproject.toml", "relay.env"):
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("backup requires regular snapshot files")
        files[name] = path.read_bytes()
    return {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}


def _record(directory):
    marker = directory / "upgrade-state.json"
    if directory.is_symlink() or marker.is_symlink() or not marker.is_file():
        raise ValueError("backup journal must be a regular file in a real directory")
    record = json.loads(marker.read_text(encoding="utf-8"))
    if (record.get("stage") not in {"started", "complete"} or not record.get("started_at")
            or not record.get("updated_at") or record.get("files") != _inventory(directory)):
        raise ValueError("backup journal or snapshot verification failed")
    version = tomllib.loads((directory / "pyproject.toml").read_text(encoding="utf-8"))["project"]["version"]
    if not version or version != record.get("relay_version"):
        raise ValueError("backup relay version verification failed")
    return record


def select_backup(layout, planned, *, explicit=False, rollback=False):
    """Validate an existing journal before choosing settings or mutating the install."""
    parent, record = layout.service / "backups", None
    if parent.is_symlink():
        raise ValueError("backup parent must not be a symlink")
    if explicit:
        record = _record(planned)
    elif parent.exists():
        for directory in sorted(parent.iterdir(), reverse=True):
            if directory.name.startswith(".upgrade-"):
                continue
            candidate = _record(directory)
            if rollback or candidate["stage"] == "started":
                planned, record = directory, candidate
                break
    if rollback and record is None:
        raise FileNotFoundError("no verified upgrade backup exists")
    if record is not None and not rollback and record["stage"] != "started":
        raise ValueError("forward retry requires a started backup")
    return planned, record


class Upgrade:
    def __init__(self, layout, settings, runner, probe, installer, *, emit=print,
                 clock=time.monotonic, sleep=time.sleep, now=timestamp, backup=None):
        self.layout, self.settings, self.runner, self.probe = layout, settings, runner, probe
        self.installer, self.emit, self.clock, self.sleep, self.now = installer, emit, clock, sleep, now
        self.backup = Path(backup) if backup is not None else layout.service / "backups" / now()
        self.explicit_backup = backup is not None
        self.backup_usable, self.current_step, self.record = False, None, None

    def _select(self, rollback):
        self.backup, self.record = select_backup(self.layout, self.backup,
                                                explicit=self.explicit_backup, rollback=rollback)
        self.backup_usable = self.record is not None

    def _preflight(self, rollback):
        self._select(rollback)
        self.runner.require(("launchctl",))
        if self.layout.service.is_symlink() or self.layout.plugin(NEW_PLUGIN).is_symlink():
            raise ValueError("installed product directories must not be symlinks")
        if rollback:
            if not self.layout.plist(NEW_AGENT).is_file():
                raise FileNotFoundError("installed relay plist is required for rollback")
            return
        for name in ("relay.env", "pyproject.toml", ".venv/bin/python", "atomic_relay/__main__.py"):
            path = self.layout.service / name
            if not path.is_file() or (name != ".venv/bin/python" and path.is_symlink()):
                raise FileNotFoundError(f"upgrade preflight requires {name}")
        if not self.layout.plist(NEW_AGENT).is_file() or read_manifest(self.layout.plugin(NEW_PLUGIN)).name != NEW_PLUGIN:
            raise ValueError("upgrade requires the installed Atomic relay plist and plugin")
        if self.runner.run(("launchctl", "print", self.layout.label(NEW_AGENT)), timeout=30) is None:
            raise ValueError("the Atomic relay service must be loaded")
        self.probe.set_secret_file(None)
        data = self.probe.get("/health", timeout=5)
        if data.get("status") != "ok":
            raise ValueError("public relay health preflight failed")

    def _backup(self):
        if self.backup_usable:
            self.emit(f"Reusing started backup: {self.backup}")
            return
        self.backup.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        staging = Path(tempfile.mkdtemp(prefix=".upgrade-", dir=self.backup.parent))
        try:
            for name, source in (("atomic_relay", self.layout.service / "atomic_relay"),
                                 (NEW_PLUGIN, self.layout.plugin(NEW_PLUGIN))):
                files = package_files(source)
                sync_package(files, staging / name)
                if package_files(staging / name) != files:
                    raise OSError("backup package verification failed")
            for name in ("pyproject.toml", "relay.env"):
                original = (self.layout.service / name).read_bytes()
                atomic_write(staging / name, original)
                if (staging / name).read_bytes() != original:
                    raise OSError("backup file verification failed")
            version = tomllib.loads((staging / "pyproject.toml").read_text(encoding="utf-8"))["project"]["version"]
            self.record = {"stage": "started", "relay_version": version, "started_at": self.now(),
                           "updated_at": self.now(), "files": _inventory(staging)}
            atomic_write(staging / "upgrade-state.json", json.dumps(self.record).encode())
            _record(staging)
            staging.rename(self.backup)
            self.backup_usable = True
        finally:
            if staging.exists():
                shutil.rmtree(staging)
        self.emit(f"Verified backup: {self.backup}")

    def _restore(self):
        _record(self.backup)
        for name, target in (("atomic_relay", self.layout.service / "atomic_relay"),
                             (NEW_PLUGIN, self.layout.plugin(NEW_PLUGIN))):
            sync_package(package_files(self.backup / name), target)
        for name in ("pyproject.toml", "relay.env"):
            atomic_write(self.layout.service / name, (self.backup / name).read_bytes())

    def _check(self, step, rollback):
        secret = self.settings.secret_file if rollback else self.layout.service / "admin.secret"
        self.probe.set_secret_file(None if step.operation == "health" else secret)
        version = self.record["relay_version"] if rollback else self.settings.new_version
        path, predicate = (("/health", lambda data: healthy(data, version)) if step.operation == "health"
                           else ("/bridge/devices", phone_connected))
        wait_for(self.probe, path, predicate, budget=step.timeout, clock=self.clock, sleep=self.sleep)

    def _operator(self, rollback):
        self.emit(f"Operator, from a separate shell: {RESTART}")
        if not rollback:
            self.emit("Accepted window: agent tools return 401 until the gateway restarts; the phone link stays up.")
            self.emit("Watcher edit and rollback: mac/README.md (Operator, after the gateway restart).")
            self.emit("APK steps (Operator): build and keep the higher-version rollback APK; check both APKs and certificates; "
                      "install release over the paired app; verify pairing, self-heal, reboot and private cleartext.")
            self.emit("Rollback APK: tools/build-rollback-apk.sh 6a8a6b7 11; "
                      "tools/check-release-apk.sh build/rollback/Atomic-rollback-6a8a6b7-11.apk --version-code 11 --debuggable")
            self.emit("Release APK: tools/build.sh :app:assembleRelease; "
                      "tools/check-release-apk.sh app/build/outputs/apk/release/app-release.apk --version-code 10; compare signing certificates before install.")

    def _env(self):
        path = self.layout.service / "relay.env"
        raw = ""
        for line in path.read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() == "ATOMIC_RELAY_HOST":
                raw = value.strip()
        if len(raw) >= 2 and raw[0] in "'\"" and raw[-1] == raw[0]:
            raw = raw[1:-1]
        values = {"ATOMIC_RELAY_TRUSTED_PROXIES": "127.0.0.1",
                  "ATOMIC_RELAY_ADMIN_SECRET_FILE": str(self.layout.service / "admin.secret"),
                  "ATOMIC_RELAY_SERVER_VERSION": self.settings.new_version}
        try:
            parse_hosts(raw)
        except ValueError:
            logger.warning("component=ops operation=upgrade-env state=host_reset reason=missing_or_invalid")
            values["ATOMIC_RELAY_HOST"] = "127.0.0.1"
        update(path, values)

    def execute(self, *, rollback=False):
        for step in build_plan(self.layout, rollback=rollback):
            self.current_step = step
            self.emit(step.description)
            if step.operation == "preflight":
                self._preflight(rollback)
            elif step.operation == "backup":
                self._backup()
            elif step.operation == "secret":
                create_if_missing(self.layout.service / "admin.secret")
            elif step.operation == "env":
                self._env()
            elif step.operation == "install":
                self.installer.install()
            elif step.operation == "command":
                missing = rollback and step.argv[1] == "bootout"
                result = self.runner.run(step.argv, timeout=step.timeout, missing_service=missing)
                if missing and result is None:
                    logger.warning("component=ops operation=rollback relay already unloaded label=%s", step.argv[-1])
            elif step.operation == "restore":
                self._restore()
            elif step.operation in {"health", "phone"}:
                self._check(step, rollback)
            elif step.operation == "operator":
                self._operator(rollback)
            elif step.operation == "complete":
                self.record.update(stage="complete", updated_at=self.now())
                atomic_write(self.backup / "upgrade-state.json", json.dumps(self.record).encode())
            else:
                raise ValueError("unknown upgrade operation")
