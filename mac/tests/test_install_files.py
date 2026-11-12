import plistlib

import pytest

from atomic_relay.ops.install_files import package_changed, package_files, render_plist, sync_package
from ops_cases import ROOT


def test_sync_removes_stale_files_only_in_owned_package(tmp_path):
    source, target = tmp_path / "source", tmp_path / "owned"
    source.mkdir()
    (source / "kept.py").write_text("new content")
    (source / "__pycache__").mkdir()
    (source / "__pycache__/cached.pyc").write_bytes(b"ignored")
    files = package_files(source)
    target.mkdir()
    (target / "stale.py").write_text("stale")
    sibling = tmp_path / "retained"
    sibling.write_text("unchanged")
    assert package_changed(files, target)
    sync_package(files, target)
    assert not package_changed(files, target)
    assert not (target / "stale.py").exists() and sibling.read_text() == "unchanged"


def test_symlink_root_is_refused_and_stale_child_link_is_not_followed(tmp_path):
    outside, link = tmp_path / "outside", tmp_path / "owned"
    outside.mkdir()
    (outside / "retained").write_text("unchanged")
    link.symlink_to(outside, target_is_directory=True)
    with pytest.raises(ValueError, match="symlink"):
        sync_package({"example.py": b"new"}, link)
    link.unlink()
    link.mkdir()
    (link / "stale-link").symlink_to(outside, target_is_directory=True)
    sync_package({}, link)
    assert (outside / "retained").is_file() and not (link / "stale-link").exists()


def test_plist_escapes_home_and_contains_only_approved_environment(tmp_path):
    home = tmp_path / "name & more"
    data = render_plist(ROOT / "mac/launchd/com.atomic.bridge-relay.plist", home)
    plist = plistlib.loads(data)
    assert plist["EnvironmentVariables"].keys() == {"HOME", "PATH"}
    assert plist["EnvironmentVariables"]["HOME"] == str(home)
    assert plist["ProgramArguments"] == [str(home / "services/atomic-bridge/.venv/bin/python"), "-m", "atomic_relay", "serve",
                                         "--env-file", str(home / "services/atomic-bridge/relay.env")]
    assert plist["RunAtLoad"] is True and plist["KeepAlive"] is True and plist["ThrottleInterval"] == 30
