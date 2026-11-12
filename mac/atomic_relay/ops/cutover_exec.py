"""Execute the plan through injected command, probe and clock interfaces."""

import logging
import time

from atomic_relay.ops.cutover_plan import NEW_AGENT, OLD_AGENT, NEW_PLUGIN, OLD_PLUGIN
from atomic_relay.ops.manifest import read_manifest
from atomic_relay.ops.plugin_verify import check_configured, check_doctor
from atomic_relay.ops.probe import healthy, phone_connected, wait_for

logger = logging.getLogger("atomic_relay.ops")


def check_files(layout):
    required = [layout.service / "relay.env", layout.service / "pyproject.toml",
                layout.service / ".venv/bin/python", layout.service / "atomic_relay/__main__.py",
                layout.plist(NEW_AGENT), layout.plist(OLD_AGENT)]
    for path in required:
        if not path.is_file():
            raise FileNotFoundError(f"required installed file missing: {path}")
    for name in (NEW_PLUGIN, OLD_PLUGIN):
        manifest = read_manifest(layout.plugin(name))
        if manifest.name != name or not (layout.plugin(name) / "__init__.py").is_file():
            raise ValueError(f"installed plugin identity or entry point is invalid: {name}")


class Cutover:
    def __init__(self, layout, settings, runner, probe, *, emit, clock=time.monotonic, sleep=time.sleep):
        self.layout, self.settings, self.runner, self.probe = layout, settings, runner, probe
        self.emit, self.clock, self.sleep = emit, clock, sleep
        self.current_loaded = True

    def preflight(self, rollback):
        check_files(self.layout)
        self.runner.require(("launchctl", "hermes"))
        current, target = (NEW_AGENT, OLD_AGENT) if rollback else (OLD_AGENT, NEW_AGENT)
        status = self.runner.run(("launchctl", "print", self.layout.label(current)), missing_service=rollback)
        self.current_loaded = status is not None
        loaded = self.runner.run(("launchctl", "print", self.layout.label(target)), missing_service=True)
        if loaded is not None:
            raise ValueError(f"target LaunchAgent is already loaded: {target}")
        if not rollback:
            self.check("/health", lambda data: healthy(data, self.settings.old_version), 0)
        elif not self.current_loaded:
            logger.warning("component=ops operation=rollback current service already unloaded label=%s; restoring old service", current)
            self.emit(f"Already unloaded (checked): {current}; restoring the old service.")

    def check(self, path, predicate, budget):
        wait_for(self.probe, path, predicate, budget=budget, clock=self.clock, sleep=self.sleep)

    def execute(self, steps, *, rollback=False, verify=False):
        selected, other = (OLD_PLUGIN, NEW_PLUGIN) if rollback else (NEW_PLUGIN, OLD_PLUGIN)
        for step in steps:
            self.current_step = step
            if step.operation != "operator":
                self.emit(step.description)
            if step.operation == "preflight":
                self.preflight(rollback)
            elif step.operation == "command":
                if rollback and step.argv[:2] == ("launchctl", "bootout") and not self.current_loaded:
                    self.emit("Skip bootout: the new service was checked and is already unloaded.")
                else:
                    self.runner.run(step.argv, timeout=step.timeout)
            elif step.operation == "configured":
                manifest = read_manifest(self.layout.plugin(selected))
                check_configured(self.runner.run(step.argv, timeout=step.timeout), manifest, other)
            elif step.operation == "doctor":
                directory = self.layout.plugin(selected)
                manifest = read_manifest(directory)
                check_doctor(self.runner.run(step.argv, timeout=step.timeout), directory, manifest)
                self.emit(f"Isolated manifest registration verified: {len(manifest.tools)} tools, {len(manifest.hooks)} hooks")
            elif step.operation == "health":
                self.check("/health", lambda data: healthy(data, self.settings.version(rollback)), 0 if verify else step.timeout)
            elif step.operation == "phone":
                self.check("/bridge/devices", phone_connected, 0 if verify else step.timeout)
            elif step.operation == "operator":
                self.emit("Before: confirm relay health, connected phone and one tool call in the existing session.")
                self.emit("After: run verify (or verify --rollback), check active loading and session tools, then make one tool call.")
                self.emit(step.description)
            elif step.operation != "unverified":
                raise ValueError(f"unknown operator step: {step.operation}")
            logger.info("component=ops operation=step completed step=%s", step.operation)
