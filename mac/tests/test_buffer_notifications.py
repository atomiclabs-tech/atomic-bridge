import pytest

from atomic_relay.bridge.registry import BridgeError
from atomic_relay.notifications.buffer import NotificationBuffer
from notification_cases import posted, removed


def test_retained_device_aliases_and_invalid_frames_are_checked():
    buffer = NotificationBuffer()
    buffer.append("one", "notification.posted", posted(), aliases={"Pixel One", "pixelone", "phone"})
    assert buffer.select_device("PIXEL ONE") == "one"
    buffer.append("two", "notification.posted", posted(), aliases={"phone"})
    for selector, expected in (("phone", 409), ("missing", 404)):
        with pytest.raises(BridgeError) as error:
            buffer.select_device(selector)
        assert error.value.status_code == expected
    for payload in (posted(key=None), posted(posted_at=True), posted(posted_at=float("nan")), posted(text=[]), posted(ongoing=1)):
        with pytest.raises(ValueError):
            buffer.append("one", "notification.posted", payload)


@pytest.mark.parametrize("kind,payload,timestamp_key,other_key", [
    ("notification.posted", posted(), "posted_at", "removed_at"),
    ("notification.removed", removed(), "removed_at", "posted_at"),
])
@pytest.mark.parametrize("include_other_timestamp", [False, True])
def test_append_requires_the_timestamp_for_its_event_type(kind, payload, timestamp_key, other_key, include_other_timestamp):
    buffer = NotificationBuffer()
    invalid = dict(payload)
    timestamp = invalid.pop(timestamp_key)
    if include_other_timestamp:
        invalid[other_key] = timestamp
    with pytest.raises(ValueError, match="notification event timestamp is invalid"):
        buffer.append("device", kind, invalid)
    assert buffer.recent() == []
