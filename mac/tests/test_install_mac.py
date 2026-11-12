from pathlib import Path

import pytest
# no_parallel: installer uses a temporary operator home and fake command runner

from atomic_relay.ops.commands import StepFailure
from ops_cases import ROOT, tool_module


class InstallerRunner:
    def __init__(self):
        self.calls = []
        self.fail = False

    def run(self, argv, *, timeout=30):
        self.calls.append((tuple(argv), timeout))
        if argv[1] == "venv":
            python = Path(argv[-1]) / "bin/python"
            python.parent.mkdir(parents=True)
            python.write_text("# isolated fake Python, never executed\n")
        if self.fail and argv[1] == "pip":
            raise StepFailure(argv, 13, "synthetic offline cache failure")
        return "isolated installer command completed\n"


def test_installer_idempotence_preserves_existing_env_and_old_artifacts(tmp_path):
    installer = tool_module("install_mac")
    home, runner, output = tmp_path / "home", InstallerRunner(), []
    service = home / "services/atomic-bridge"
    service.mkdir(parents=True)
    env = service / "relay.env"
    env.write_text("# operator-private environment preserved\n")
    old = home / ".hermes/plugins/hermes-relay/retained"
    old.parent.mkdir(parents=True)
    old.write_text("unchanged old plugin")
    sessions = home / ".hermes/hermes-relay-sessions.json"
    sessions.write_text("isolated retained sessions sentinel")
    old_plist = home / "Library/LaunchAgents/com.atomic.hermes-relay.plist"
    old_plist.parent.mkdir(parents=True)
    old_plist.write_text("unchanged old plist")
    target = home / ".hermes/plugins/atomic-bridge"
    target.mkdir()
    (target / "stale.py").write_text("stale")
    install = installer.Installer(ROOT, home, runner, emit=output.append, uv="fake-uv")
    assert install.install() > 0
    assert output[-1] == "Next: tools/pair.sh  (starts the relay if needed, then pairs your phone)"
    secret = service / "admin.secret"
    assert secret.is_file() and secret.stat().st_mode & 0o777 == 0o600
    first_secret = secret.read_bytes()
    assert not (target / "stale.py").exists()
    assert env.read_text() == "# operator-private environment preserved\n"
    assert old.read_text() == "unchanged old plugin" and sessions.read_text() == "isolated retained sessions sentinel"
    assert old_plist.read_text() == "unchanged old plist"
    calls = len(runner.calls)
    output.clear()
    assert install.install() == 0 and output == ["Nothing to do.",
        "Next: tools/pair.sh  (starts the relay if needed, then pairs your phone)"]
    assert len(runner.calls) == calls
    assert secret.read_bytes() == first_secret
    assert all(call[0][0] != "launchctl" for call in runner.calls)
    assert all("--offline" in call[0] for call in runner.calls if call[0][0] == "fake-uv")


def test_installer_dependency_failure_keeps_missing_marker_and_raises(tmp_path):
    runner = InstallerRunner()
    runner.fail = True
    with pytest.raises(StepFailure, match="synthetic offline cache failure"):
        tool_module("install_mac").Installer(ROOT, tmp_path / "home", runner, emit=lambda line: None, uv="fake-uv").install()
    assert not (tmp_path / "home/services/atomic-bridge/.venv/atomic-install.sha256").exists()


def test_installer_missing_source_fails_before_creating_home(tmp_path):
    home = tmp_path / "not-created"
    with pytest.raises(FileNotFoundError):
        tool_module("install_mac").Installer(tmp_path, home, InstallerRunner()).install()
    assert not home.exists()
