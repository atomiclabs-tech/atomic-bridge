import pytest

from atomic_relay.bridge.registry import BridgeError, DeviceRegistry
from bridge_cases import ready_registry


def test_no_ready_phone_is_unavailable_and_last_status_remains_after_disconnect():
    registry = DeviceRegistry()
    with pytest.raises(BridgeError) as error:
        registry.resolve()
    assert error.value.status_code == 503
    registry, socket = ready_registry()
    socket.closed = True
    registry.detach(socket, "test disconnected")
    assert not registry.status_payload()["phone_connected"]
    assert registry.devices_payload() == {"active_device_id": None, "most_recent_device_id": None, "devices": [], "count": 0}
    with pytest.raises(BridgeError) as error:
        registry.resolve("device")
    assert error.value.status_code == 404
