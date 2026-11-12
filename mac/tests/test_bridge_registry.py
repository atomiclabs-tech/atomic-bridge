import pytest

from atomic_relay.bridge.registry import BridgeError, DeviceRegistry
from bridge_cases import Socket, session


def test_alias_selection_active_mostrecent_and_ambiguity():
    now = [0]
    registry = DeviceRegistry(clock=lambda: now[0])
    one, two = Socket(), Socket()
    registry.update(one, {"battery": 20}, session("one", "Pixel One"))
    now[0] = 2
    registry.update(two, {"battery": 80}, session("two", "Pixel Two"))
    assert registry.active_device_id == "one" and registry.most_recent_device_id == "two"
    assert registry.resolve()[0].socket is one
    assert registry.resolve("PIXEL ONE")[0].socket is one
    for selector, code in (("phone", 409), ("missing", 404)):
        with pytest.raises(BridgeError) as error:
            registry.resolve(selector)
        assert error.value.status_code == code
    selected = registry.select_active("Pixel Two")
    assert selected == {"ok": True, "active_device_id": "two", "device_id": "two", "device_name": "Pixel Two",
                        "alias_used": "Pixel Two", "aliases": sorted(registry.clients["two"].aliases)}
    two.closed = True
    registry.detach(two, "test disconnected")
    assert registry.active_device_id == "one" and registry.most_recent_device_id == "one"
    assert registry.resolve()[0].socket is one


def test_replacement_and_late_old_disconnect_cannot_detach_current_registration():
    registry, old, new = DeviceRegistry(), Socket(), Socket()
    registry.update(old, {"battery": 1}, session())
    assert registry.update(new, {"battery": 2}, session()) is old
    registry.detach(old, "old disconnected")
    assert registry.resolve()[0].socket is new
    assert registry.devices_payload()["count"] == 1
    assert registry.status_payload()["battery"] == 2
