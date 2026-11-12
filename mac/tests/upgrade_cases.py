"""Temporary installation and recording ports for upgrade failure proofs."""

from pathlib import Path
import secrets
import shlex
import shutil
import urllib.error

from atomic_relay import __version__
from atomic_relay.ops.install_files import package_files
from atomic_relay.ops.secret import read_secret
from ops_cases import FakeClock, FakeProbe, ROOT, installed, tool_module
from test_install_mac import InstallerRunner


class UpgradeRunner(InstallerRunner):
    def __init__(self):
        super().__init__()
        self.requirements = []

    def require(self, names):
        self.requirements.append(names)

    def run(self, argv, *, timeout=30, missing_service=False):
        if argv[0] != "launchctl":
            return super().run(argv, timeout=timeout)
        self.calls.append((tuple(argv), timeout))
        return "fake relay loaded" if argv[1] == "print" else "fake relay command completed"


class UpgradeProbe(FakeProbe):
    def __init__(self, versions=("1.19.3", __version__)):
        super().__init__(versions)
        self.secret_file, self.credentials = None, []
        self.expected_secret_file, self.refuse = None, False

    def set_secret_file(self, path):
        self.secret_file = path

    def get(self, path, *, timeout=5):
        self.credentials.append((path, self.secret_file))
        if path == "/bridge/devices" and self.expected_secret_file is not None:
            expected = read_secret(self.expected_secret_file)
            supplied = read_secret(self.secret_file) if self.secret_file is not None else ""
            if self.refuse or not secrets.compare_digest(supplied, expected):
                self.calls.append((path, timeout))
                raise urllib.error.HTTPError(path, 401, "admin credential required", None, None)
        return super().get(path, timeout=timeout)


def case(tmp_path):
    layout = installed(tmp_path)
    project = layout.service / "pyproject.toml"
    project.write_text(project.read_text().replace(f'version = "{__version__}"', 'version = "1.19.3"'))
    shutil.rmtree(layout.plugin("hermes-relay"))
    old = layout.plugin("atomic-bridge")
    shutil.rmtree(old)
    old.mkdir()
    (old / "plugin.yaml").write_text("name: atomic-bridge\nversion: 1.19.3\nprovides_tools:\n  - android_ping\n")
    (old / "__init__.py").write_bytes(b"# retained old Atomic plugin\n")
    (layout.service / "relay.env").write_bytes(b'# preserve comment\nUNKNOWN="quote # preserved"\nATOMIC_RELAY_PORT=19876\n')
    sentinels = {layout.home / ".hermes/hermes-relay-sessions.json": b"synthetic retained session bytes",
                 layout.home / ".hermes/.env": b"synthetic unrelated environment",
                 layout.home / ".hermes/scripts/atomic_phone_watch.py": b"synthetic watcher sentinel"}
    for path, data in sentinels.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    runner, probe, clock, output = UpgradeRunner(), UpgradeProbe(), FakeClock(), []
    installer = tool_module("install_mac").Installer(ROOT, layout.home, runner, emit=output.append, uv="fake-uv")
    module = tool_module("upgrade")
    options = dict(layout=layout, runner=runner, probe=probe, installer=installer, emit=output.append,
                   clock=clock.clock, sleep=clock.sleep, now=lambda: "20261110T000000.000000Z")
    return module, layout, runner, probe, output, options, sentinels


def admin_backup_case(tmp_path, *, home_relative=False):
    context = case(tmp_path)
    _, layout, _, probe, _, _, _ = context
    secret = layout.home / "private credentials/backup-admin.secret"
    secret.parent.mkdir(mode=0o700)
    secret.write_text("synthetic-" + secrets.token_hex(32) + "\n")
    secret.chmod(0o600)
    raw_path = "~/private credentials/backup-admin.secret" if home_relative else str(secret)
    env = layout.service / "relay.env"
    env.write_text(env.read_text() + f"ATOMIC_RELAY_ADMIN_SECRET_FILE={shlex.quote(raw_path)}\n")
    for path in (layout.service / "pyproject.toml", layout.plugin("atomic-bridge") / "plugin.yaml"):
        path.write_text(path.read_text().replace("1.19.3", "2.2.0"))
    probe.versions = ["2.2.0", __version__]
    return context, secret


def snapshot(layout):
    return {"relay": package_files(layout.service / "atomic_relay"),
            "plugin": package_files(layout.plugin("atomic-bridge")),
            "project": (layout.service / "pyproject.toml").read_bytes(),
            "env": (layout.service / "relay.env").read_bytes()}


def backup_dir(layout):
    return layout.service / "backups/20261110T000000.000000Z"
