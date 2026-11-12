import pytest

from atomic_relay.notifications.buffer import NotificationBuffer
from notification_cases import posted, removed


def test_per_device_500_dedup_and_authoritative_server_metadata():
    buffer = NotificationBuffer(clock=lambda: 5)
    for index in range(501):
        assert buffer.append("one", "notification.posted", posted(str(index), index))
    assert len(buffer.devices["one"]) == 500 and buffer.devices["one"][0]["key"] == "1"
    assert buffer.append("one", "notification.posted", posted("500", 500)) is None
    second = buffer.append("two", "notification.posted", posted("500", 500, device_id="spoofed", received_at=0, sequence=0))
    assert second["device_id"] == "two" and second["received_at"] == 5000 and second["sequence"] == 502
    assert len(buffer.recent(limit=500, device="one")) == 500
    assert len(buffer.devices["two"]) == 1


def test_posted_and_removed_frames_remain_truthful_and_filters_use_event_time():
    buffer = NotificationBuffer()
    first = buffer.append("device", "notification.posted", posted(at=100, text="one"))
    last = buffer.append("device", "notification.removed", removed(at=101, reason=2))
    assert last["type"] == "notification.removed" and last["removed_at"] == 101
    assert "posted_at" not in last and "title" not in last and "text" not in last
    assert buffer.append("device", "notification.removed", removed(at=101, reason=2)) is None
    assert [row["type"] for row in buffer.recent()] == ["notification.removed", "notification.posted"]
    assert buffer.recent(since=99) == [last, first]
    assert buffer.recent(since=100) == [last]
    assert buffer.recent(since=101) == []
    assert buffer.recent(text="ONE") == [first]
    assert buffer.recent(since=99, text="ONE") == [first]


@pytest.mark.parametrize("kind,payload", [
    ("notification.posted", posted(at=100)),
    ("notification.removed", removed(at=100, reason=8)),
])
@pytest.mark.parametrize("since,included", [(50, True), (100, False), (150, False)])
def test_since_uses_the_event_timestamp_with_a_strict_boundary(kind, payload, since, included):
    buffer = NotificationBuffer()
    entry = buffer.append("device", kind, payload)
    assert buffer.recent(since=since) == ([entry] if included else [])


def test_recent_filters_all_text_fields_newest_first_and_preserves_nullable_producer_text():
    buffer = NotificationBuffer()
    nulls = buffer.append("one", "notification.posted", posted("null", 100, title=None, text=None, sub_text=None))
    assert nulls["title"] is None and nulls["text"] is None and nulls["sub_text"] is None
    assert buffer.recent(text="missing") == []
    for index, field in enumerate(("title", "text", "sub_text"), 101):
        buffer.append("one", "notification.posted", posted(str(index), index, **{field: "Match mixed CASE"}))
    buffer.append("two", "notification.posted", posted("other", 104, package_name="other.package"))
    assert [row["posted_at"] for row in buffer.recent(text="MIXED case", package="synthetic.package", device="one", limit=2)] == [103, 102]
    assert len(buffer.recent(package="other.package")) == 1
