#!/usr/bin/env python3
"""Idempotently install Atomic's Mac files; no LaunchAgent is loaded or started."""

import argparse
import hashlib
import logging
from pathlib import Path
import shutil
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mac"))

from atomic_relay.logsetup import configure_startup_logging  # noqa: E402
from atomic_relay.ops.commands import CommandRunner  # noqa: E402
from atomic_relay.ops.install_files import (  # noqa: E402
    package_changed, package_files, render_plist, sync_package, write_file,
)
from atomic_relay.ops.layout import Layout  # noqa: E402
from atomic_relay.ops.secret import create_if_missing, read_secret  # noqa: E402

logger = logging.getLogger("atomic_relay.ops")


class Installer:
    def __init__(self, root, home, runner, *, emit=print, uv="uv"):
        self.root, self.layout, self.runner, self.emit, self.uv = root, Layout(home, 0), runner, emit, uv

    def _sources(self):
        sources = (self.root / "mac/pyproject.toml", self.root / "mac/.env.example",
                   self.root / "mac/launchd/com.atomic.bridge-relay.plist")
        for source in sources:
            if not source.is_file():
                raise FileNotFoundError(f"required installer source missing: {source}")
        for path in (self.layout.home, self.layout.service, self.layout.plugin("atomic-bridge").parent,
                     self.layout.plist("com.atomic.bridge-relay").parent):
            if path.is_symlink():
                raise ValueError(f"installation parent must not be a symlink: {path}")
        return sources

    def actions(self):
        project, example, template = self._sources()
        actions = []
        for package, target in (("atomic_relay", self.layout.service / "atomic_relay"),
                                ("atomic_bridge", self.layout.plugin("atomic-bridge"))):
            files = package_files(self.root / "mac" / package)
            if package_changed(files, target):
                actions.append((f"Copy {package} to {target}; remove stale files within this owned package only",
                                lambda files=files, target=target: sync_package(files, target)))
        project_data = project.read_bytes()
        target_project = self.layout.service / "pyproject.toml"
        if not target_project.exists() or target_project.read_bytes() != project_data:
            actions.append((f"Copy pyproject.toml to {target_project}", lambda: write_file(target_project, project_data)))
        marker = self.layout.service / ".venv/atomic-install.sha256"
        fingerprint = hashlib.sha256(project_data).hexdigest().encode()
        python = self.layout.service / ".venv/bin/python"
        if not python.is_file() or not marker.is_file() or marker.read_bytes() != fingerprint:
            actions.append(("Build/refresh offline venv: uv venv --python 3.12 --offline; uv pip install --offline aiohttp==3.14.3",
                            lambda: self._venv(python, marker, fingerprint)))
        env = self.layout.service / "relay.env"
        if not env.exists():
            actions.append((f"Create env-file-if-missing {env} (preserve when present)",
                            lambda: write_file(env, example.read_bytes(), mode=0o600)))
        secret = self.layout.service / "admin.secret"
        if secret.exists() or secret.is_symlink():
            read_secret(secret)
        else:
            actions.append((f"Create admin secret if missing {secret} (0600)", lambda: create_if_missing(secret)))
        for name in ("logs", "media"):
            directory = self.layout.service / name
            if directory.is_symlink() or (directory.exists() and not directory.is_dir()):
                raise ValueError(f"installation directory is invalid: {directory}")
            if not directory.exists():
                actions.append((f"Create private directory {directory}",
                                lambda directory=directory: directory.mkdir(parents=True, mode=0o700)))
        plist = self.layout.plist("com.atomic.bridge-relay")
        data = render_plist(template, self.layout.home)
        if not plist.exists() or plist.read_bytes() != data:
            actions.append((f"Write plist {plist}", lambda: write_file(plist, data)))
        return actions

    def _venv(self, python, marker, fingerprint):
        if not python.is_file():
            self.runner.run((self.uv, "venv", "--python", "3.12", "--offline", str(python.parent.parent)), timeout=120)
        self.runner.run((self.uv, "pip", "install", "--offline", "--python", str(python), "aiohttp==3.14.3"), timeout=120)
        self.runner.run((str(python), "-c", "import importlib.metadata as m; assert m.version('aiohttp') == '3.14.3'"), timeout=30)
        write_file(marker, fingerprint, mode=0o600)

    def install(self, *, dry_run=False):
        actions = self.actions()
        if not actions:
            self.emit("Nothing to do.")
        for description, action in actions:
            self.emit(description)
            if not dry_run:
                action()
        self.emit("Next: tools/pair.sh  (starts the relay if needed, then pairs your phone)")
        return len(actions)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    configure_startup_logging()
    try:
        uv = shutil.which("uv")
        if not args.dry_run and uv is None:
            raise FileNotFoundError("uv is required for the offline installer")
        root = Path(__file__).resolve().parents[1]
        Installer(root, Path.home(), CommandRunner(), uv=uv or "uv").install(dry_run=args.dry_run)
    except Exception as error:
        safe = RuntimeError("installation failed; command and response details withheld")
        logger.exception("component=ops operation=install failed",
                         exc_info=(RuntimeError, safe, error.__traceback__))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
