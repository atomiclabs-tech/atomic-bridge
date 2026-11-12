"""The actual upgrade CLI dry-run returns before resolving or reading any home."""
# no_parallel: subprocess CLI proof uses a guarded temporary home

import os
from pathlib import Path
import subprocess

import pytest

from ops_cases import ROOT, isolated_python_env, tool_module


@pytest.mark.parametrize("args", [[], ["--rollback"], ["--rollback", "--backup", "/unread/backup"]])
def test_dry_run_never_resolves_home_or_reads_configuration(tmp_path, monkeypatch, args):
    module = tool_module("upgrade")
    def forbidden(*args, **kwargs):
        raise AssertionError("dry-run attempted host I/O")
    monkeypatch.setattr(Path, "home", forbidden)
    monkeypatch.setattr(os, "getuid", forbidden)
    monkeypatch.setattr(module, "load_settings", forbidden)
    monkeypatch.setattr(Path, "open", forbidden)
    monkeypatch.setattr(Path, "stat", forbidden)
    output = []
    assert module.main([*args, "--dry-run"], emit=output.append) == 0
    assert "Operator, from a separate shell" in "\n".join(output)


@pytest.mark.parametrize("args", [[], ["--rollback"]])
def test_actual_upgrade_cli_dry_run_in_isolated_subprocess(tmp_path, args):
    home = tmp_path / "unread home"
    env = isolated_python_env(tmp_path, home)
    result = subprocess.run([str(ROOT / "tools/py.sh"), "../tools/upgrade.py", *args, "--dry-run"],
                            check=True, timeout=10, capture_output=True, text=True, env=env)
    assert "launchctl" in result.stdout and "Operator, from a separate shell" in result.stdout
    assert not home.exists() and not result.stderr
