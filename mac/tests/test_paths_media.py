import pytest

from atomic_relay.media.paths import MediaError, MediaPaths
from media_cases import path_policy


def test_configured_sessions_path_is_denied_and_prefix_sibling_is_not_allowed(tmp_path, monkeypatch):
    policy = path_policy(tmp_path, monkeypatch)
    protected = tmp_path / "allowed" / "custom-sessions.json"
    protected.write_text("synthetic")
    policy = MediaPaths(policy.roots, policy.media_dir, policy.max_bytes, sessions_file=protected, home=tmp_path / "home")
    with pytest.raises(MediaError):
        policy.validate(protected)
    sibling = tmp_path / "allowed-sibling"
    sibling.mkdir()
    file = sibling / "source"
    file.write_text("synthetic")
    with pytest.raises(MediaError):
        policy.validate(file)
    temporary = tmp_path / "system" / "source"
    temporary.write_text("synthetic")
    with pytest.raises(MediaError) as error:
        policy.validate(temporary)
    assert error.value.status == 403
