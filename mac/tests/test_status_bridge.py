from atomic_relay.bridge.status import derive_aliases
from bridge_cases import ready_registry, session


def test_alias_families_and_phone_owned_device_dictionary_unchanged():
    assert {"boox", "notemax", "note", "tablet"} <= derive_aliases("id", "Onyx Note Max", "android", "eink")
    assert {"phone", "fold", "pixel"} <= derive_aliases("id", "Pixel", "android", "phone")
    assert "tablet" in derive_aliases("id", "Synthetic", "android", "tablet")
    registry, socket = ready_registry(clock=lambda: 10)
    device = {"display_name": "Synthetic", "id": "phone-reported", "nested": {"extra": True}}
    registry.update(socket, {"device": device}, session())
    result = registry.status_payload()
    assert result["device"] == device and "device_id" not in device and "name" not in device
    assert result["device_id"] == "device" and result["last_seen_seconds_ago"] == 0
    assert result["active"] and result["connected"] and result["phone_connected"]
