import pytest

from atomic_relay.media.paths import MediaError
from media_cases import path_policy


@pytest.mark.parametrize("name", [".netrc", ".git-credentials", ".npmrc", ".pypirc", ".bundle/settings",
                                  ".gradle/gradle.properties", "Library/Application Support/entry"])
def test_new_home_credential_locations_are_denied_even_when_home_is_allowed(tmp_path, monkeypatch, name):
    policy = path_policy(tmp_path, monkeypatch)
    home = tmp_path / "home"
    policy.roots = (home,)
    source = home / name
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_bytes(b"synthetic")
    with pytest.raises(MediaError) as error:
        policy.validate(source)
    assert error.value.status == 403


def test_only_configured_roots_and_media_upload_paths_are_allowed_and_owned(tmp_path, monkeypatch):
    policy = path_policy(tmp_path, monkeypatch)
    assert policy.roots == (tmp_path / "allowed",)
    managed = policy.media_dir / "atomic-upload-synthetic"
    managed.write_bytes(b"synthetic")
    with pytest.raises(MediaError) as error:
        policy.validate(managed)
    assert error.value.status == 403
    assert policy.validate(managed, upload=True) == managed and policy.owned(managed)
    for parent in (tmp_path / "system", tmp_path / "allowed"):
        for name in ("atomic-upload-synthetic", "hermes-relay-upload-synthetic", "android_screenshot_synthetic"):
            assert not policy.owned(parent / name)
    assert not policy.owned(policy.media_dir / "caller-file")


def test_absolute_real_regular_root_and_cap_checks(tmp_path, monkeypatch):
    policy = path_policy(tmp_path, monkeypatch, cap=4)
    source = tmp_path / "allowed" / "source"
    source.write_bytes(b"data")
    assert policy.validate(str(source)) == source
    link = source.with_name("link")
    link.symlink_to(source)
    assert policy.validate(link) == source
    for path in ("relative", tmp_path / "allowed", tmp_path / "outside" / "missing"):
        with pytest.raises(MediaError):
            policy.validate(path)
    source.write_bytes(b"large")
    with pytest.raises(MediaError) as error:
        policy.validate(source)
    assert error.value.status == 413
    outside = tmp_path / "outside" / "target"
    outside.write_bytes(b"x")
    link.unlink()
    link.symlink_to(outside)
    with pytest.raises(MediaError) as error:
        policy.validate(link)
    assert error.value.status == 403


def test_credentials_denied_inside_an_allowed_root_without_opening_them(tmp_path, monkeypatch):
    policy = path_policy(tmp_path, monkeypatch)
    policy.roots = (tmp_path,)
    targets = [tmp_path / "home" / ".ssh" / "private", tmp_path / "home" / ".hermes" / ".env",
               tmp_path / "home" / ".hermes" / "mcp-tokens" / "private"]
    for path in targets:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"synthetic")
        with pytest.raises(MediaError) as error:
            policy.validate(path)
        assert error.value.status == 403
