import asyncio

import pytest

from atomic_relay.notifications.buffer import NotificationBuffer
from atomic_relay.notifications.waiters import NotificationWaiters
from notification_cases import posted, removed


def test_parallel_waiters_take_first_matching_posted_event_and_cleanup():
    async def check():
        buffer, requests = NotificationBuffer(), []
        waiters = NotificationWaiters(buffer)
        for _ in range(3):
            requests.append(asyncio.create_task(waiters.wait(timeout_s=1, since=100, text="match", device="one")))
        await asyncio.sleep(0)
        assert len(waiters.pending) == 3
        waiters.publish(buffer.append("one", "notification.removed", removed(at=101)))
        waiters.publish(buffer.append("two", "notification.posted", posted(at=102, text="match")))
        waiters.publish(buffer.append("one", "notification.posted", posted(at=100, text="match")))
        assert all(not request.done() for request in requests)
        first = buffer.append("one", "notification.posted", posted("first", 103, text="MATCH"))
        waiters.publish(first)
        waiters.publish(buffer.append("one", "notification.posted", posted("second", 104, text="match")))
        results = await asyncio.wait_for(asyncio.gather(*requests), timeout=2)
        assert all(result == {"ok": True, "matched": True, "notification": first} for result in results)
        assert waiters.pending == {}
    asyncio.run(check())


def test_history_timeout_cancellation_and_shutdown_leave_no_waiters():
    async def check():
        buffer = NotificationBuffer()
        waiters = NotificationWaiters(buffer)
        entry = buffer.append("one", "notification.posted", posted(at=10))
        assert (await waiters.wait(timeout_s=1, since=9))["notification"] == entry
        result = await waiters.wait(timeout_s=0.001, since=10)
        assert result["ok"] and not result["matched"] and result["waited_s"] >= 0
        task = asyncio.create_task(waiters.wait(timeout_s=1, since=10))
        await asyncio.sleep(0)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert waiters.pending == {}
        task = asyncio.create_task(waiters.wait(timeout_s=1, since=10))
        await asyncio.sleep(0)
        waiters.close()
        assert not (await task)["matched"] and waiters.pending == {}
    asyncio.run(check())
