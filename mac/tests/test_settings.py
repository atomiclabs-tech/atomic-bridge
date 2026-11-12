import pytest
# no_parallel: settings read a temporary operator home

import shlex

from atomic_relay import __version__
from atomic_relay.ops.settings import load_settings
from ops_cases import ROOT, installed


def test_settings_use_installed_versions_and_configured_port(tmp_path):
    layout = installed(tmp_path)
    env = layout.service / "relay.env"
    env.write_text("ATOMIC_RELAY_PORT=19876\nATOMIC_RELAY_SERVER_VERSION=operator-version\n")
    settings = load_settings(layout, ROOT / "mac/.env.example")
    assert settings.url == "http://127.0.0.1:19876"
    assert settings.version(False) == "operator-version" and settings.version(True) == "1.13.1"
    env.write_text("ATOMIC_RELAY_PORT=19876\n")
    assert load_settings(layout, ROOT / "mac/.env.example").new_version == "2.3.0"


@pytest.mark.parametrize("value", [
    "ANDROID_BRIDGE_URL=https://127.0.0.1:1", "ANDROID_BRIDGE_URL=http://outside.example:1",
    "ANDROID_BRIDGE_URL=http://127.0.0.1:1/path", "ANDROID_BRIDGE_URL=http://user:pass@127.0.0.1:1",
    "ATOMIC_RELAY_PORT=0", "ATOMIC_RELAY_PORT=65536", "ATOMIC_RELAY_PORT=invalid",
    "ATOMIC_RELAY_PORT=\"unterminated", "invalid assignment",
])
def test_invalid_or_nonlocal_configuration_fails(tmp_path, value):
    layout = installed(tmp_path)
    (layout.service / "relay.env").write_text(value + "\n")
    with pytest.raises(ValueError):
        load_settings(layout, ROOT / "mac/.env.example")


def test_secret_home_expansion_is_injected_and_upgrade_uses_relay_not_retired_plugin(tmp_path, monkeypatch):
    from pathlib import Path
    import shutil
    layout = installed(tmp_path)
    project = layout.service / "pyproject.toml"
    project.write_text(project.read_text().replace(f'version = "{__version__}"', 'version = "1.19.3"'))
    (layout.service / "relay.env").write_text("ATOMIC_RELAY_ADMIN_SECRET_FILE=~/services/atomic-bridge/admin.secret\n")
    shutil.rmtree(layout.plugin("hermes-relay"))
    def forbidden(*args):
        raise AssertionError("real home expansion")
    monkeypatch.setattr(Path, "home", forbidden)
    settings = load_settings(layout, ROOT / "mac/.env.example", upgrade=True)
    assert settings.secret_file == layout.service / "admin.secret"
    assert settings.new_version == "2.3.0" and settings.old_version == "1.19.3"


@pytest.mark.parametrize("upgrade", [False, True], ids=["current-mode", "upgrade-mode"])
@pytest.mark.parametrize("kind", ["absent", "empty", "absolute", "home-relative"])
def test_snapshot_admin_setting_uses_only_backup_assignments(tmp_path, upgrade, kind):
    layout = installed(tmp_path)
    backup = tmp_path / "backup"
    backup.mkdir()
    project = (layout.service / "pyproject.toml").read_bytes()
    (backup / "pyproject.toml").write_bytes(project)
    absolute = tmp_path / "snapshot admin.secret"
    raw, expected = {
        "absent": (None, None),
        "empty": ("", None),
        "absolute": (str(absolute), absolute),
        "home-relative": ("~/private credentials/snapshot-admin.secret",
                          layout.home / "private credentials/snapshot-admin.secret"),
    }[kind]
    text = "ATOMIC_RELAY_PORT=19876\nATOMIC_RELAY_SERVER_VERSION=snapshot-version\n"
    if raw is not None:
        text += f"ATOMIC_RELAY_ADMIN_SECRET_FILE={shlex.quote(raw)}\n"
    env = backup / "relay.env"
    env.write_text(text)
    settings = load_settings(layout, ROOT / "mac/.env.example", upgrade=upgrade, source=backup)
    assert settings.secret_file == expected
    assert settings.url == "http://127.0.0.1:19876"
    assert settings.new_version == (__version__ if upgrade else "snapshot-version")
    assert settings.old_version == (__version__ if upgrade else "1.13.1")
    assert env.read_text() == text and (backup / "pyproject.toml").read_bytes() == project


@pytest.mark.parametrize("upgrade", [False, True], ids=["current", "forward"])
def test_current_and_forward_settings_keep_the_admin_default(tmp_path, upgrade):
    layout = installed(tmp_path)
    (layout.service / "relay.env").write_text("ATOMIC_RELAY_PORT=19876\n")
    settings = load_settings(layout, ROOT / "mac/.env.example", upgrade=upgrade)
    assert settings.secret_file == layout.service / "admin.secret"
    assert settings.url == "http://127.0.0.1:19876" and settings.new_version == __version__
    assert settings.old_version == (__version__ if upgrade else "1.13.1")
