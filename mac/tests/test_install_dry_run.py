import subprocess
# no_parallel: shell entrypoint is isolated with a temporary home and subprocess guard

from ops_cases import ROOT, isolated_python_env, tool_module
from test_install_mac import InstallerRunner


def test_dry_run_lists_all_actions_without_mutations_or_commands(tmp_path):
    home, output, runner = tmp_path / "uncreated home", [], InstallerRunner()
    count = tool_module("install_mac").Installer(ROOT, home, runner, emit=output.append, uv="fake-uv").install(dry_run=True)
    text = "\n".join(output)
    assert count >= 7 and not home.exists() and not runner.calls
    for action in ("Copy atomic_relay", "Copy atomic_bridge", "offline venv", "env-file-if-missing", "Write plist"):
        assert action in text
    assert "launchctl" not in text


def test_shell_entrypoint_dry_run_and_strict_mode(tmp_path):
    home = tmp_path / "isolated shell home"
    env = isolated_python_env(tmp_path, home)
    result = subprocess.run([str(ROOT / "tools/install-mac.sh"), "--dry-run"],
                            check=False, timeout=10, capture_output=True, text=True, env=env)
    assert result.returncode == 0, result.stderr
    assert "Copy atomic_relay" in result.stdout and "launchctl" not in result.stdout
    assert "Create admin secret if missing" in result.stdout and str(home) in result.stdout
    assert not home.exists()
    tool_module("install_mac").Installer(ROOT, home, InstallerRunner(), emit=lambda text: None, uv="fake-uv").install()
    result = subprocess.run([str(ROOT / "tools/install-mac.sh"), "--dry-run"],
                            check=False, timeout=10, capture_output=True, text=True, env=env)
    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == [
        "Nothing to do.",
        "Next: tools/pair.sh  (starts the relay if needed, then pairs your phone)",
    ]
    shell = (ROOT / "tools/install-mac.sh").read_text()
    assert "set -euo pipefail" in shell and len(shell.splitlines()) < 100
