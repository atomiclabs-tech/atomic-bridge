#!/usr/bin/env python3
"""Reversibly upgrade the relay: tools/py.sh ../tools/upgrade.py [--rollback]."""

import argparse
import logging
import os
import shlex
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mac"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from atomic_relay.logsetup import configure_startup_logging  # noqa: E402
from atomic_relay.ops.commands import CommandRunner, StepFailure  # noqa: E402
from atomic_relay.ops.layout import Layout  # noqa: E402
from atomic_relay.ops.probe import Probe  # noqa: E402
from atomic_relay.ops.settings import load_settings  # noqa: E402
from atomic_relay.ops.upgrade_exec import Upgrade, select_backup, timestamp  # noqa: E402
from atomic_relay.ops.upgrade_plan import build_plan, render_plan, rollback_command  # noqa: E402
from install_mac import Installer  # noqa: E402

logger = logging.getLogger("atomic_relay.ops")


def main(argv=None, *, layout=None, defaults=None, runner=None, probe=None, installer=None,
         emit=print, clock=None, sleep=None, now=timestamp):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rollback", action="store_true")
    parser.add_argument("--backup", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    if args.dry_run:
        emit(render_plan(build_plan(rollback=args.rollback)))
        return 0
    configure_startup_logging()
    executor, backup, usable = None, args.backup, False
    try:
        layout = layout if layout is not None else Layout(Path.home(), os.getuid())
        backup = backup if backup is not None else layout.service / "backups" / now()
        backup, record = select_backup(layout, backup, explicit=args.backup is not None, rollback=args.rollback)
        usable = record is not None
        root = Path(__file__).resolve().parents[1]
        settings = load_settings(layout, defaults if defaults is not None else root / "mac/.env.example",
                                 upgrade=True, source=backup if args.rollback else None)
        runner = runner if runner is not None else CommandRunner()
        options = {"emit": emit, "now": now, "backup": backup if usable or args.backup is not None else None}
        if clock is not None:
            options["clock"] = clock
        if sleep is not None:
            options["sleep"] = sleep
        executor = Upgrade(layout, settings, runner, probe if probe is not None else Probe(settings.url),
                           installer if installer is not None else Installer(root, layout.home, runner, emit=emit), **options)
        backup = executor.backup
        executor.execute(rollback=args.rollback)
    except Exception as error:
        safe = RuntimeError("upgrade failed; command output and response details redacted")
        logger.exception("component=ops operation=upgrade failed", exc_info=(RuntimeError, safe, error.__traceback__))
        phase = executor.current_step.description if executor is not None and executor.current_step else "configuration preflight"
        command = shlex.join(error.argv) if isinstance(error, StepFailure) else phase
        code = error.code if isinstance(error, StepFailure) else 1
        emit(f"command: {command}\nexit code: {code}\noutput:\n[redacted; see failed operation above]")
        if executor is not None:
            backup = executor.backup
        if not (executor.backup_usable if executor is not None else usable):
            emit("No usable backup exists yet at the planned rollback directory.")
        emit(f"Rollback command: {rollback_command(backup if backup is not None else '<backup-directory>')}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
