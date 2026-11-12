from atomic_relay.ops.layout import Layout


def test_locations_are_derived_from_the_supplied_home(tmp_path):
    layout = Layout(tmp_path / "a home", 42)
    assert layout.service == tmp_path / "a home/services/atomic-bridge"
    assert layout.plugin("atomic-bridge") == layout.home / ".hermes/plugins/atomic-bridge"
    assert layout.plist("example") == layout.home / "Library/LaunchAgents/example.plist"
    assert layout.label("example") == "gui/42/example"
