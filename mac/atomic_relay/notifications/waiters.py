# Ported from plugin/relay/channels/notifications.py at upstream commit da782a8.
"""Concurrent event waiters, retaining the first matching posted event only."""

import asyncio
import time
import uuid

from atomic_relay.notifications.buffer import matches


class NotificationWaiters:
    def __init__(self, buffer, *, clock=time.monotonic, wait_for=asyncio.wait_for):
        self.buffer, self.clock, self.wait_for, self.pending = buffer, clock, wait_for, {}

    def publish(self, entry):
        if entry is None or entry["type"] != "notification.posted":
            return
        for waiter in tuple(self.pending.values()):
            if waiter["result"] is None and matches(entry, posted_only=True, **waiter["filters"]):
                waiter["result"] = dict(entry)
                waiter["event"].set()

    async def wait(self, *, timeout_s, **filters):
        started, identity = self.clock(), str(uuid.uuid4())
        waiter = {"event": asyncio.Event(), "filters": filters, "result": None}
        self.pending[identity] = waiter
        try:
            found = self.buffer.first_posted(**filters)
            if found is not None:
                return {"ok": True, "matched": True, "notification": found}
            try:
                await self.wait_for(waiter["event"].wait(), timeout=timeout_s)
            except TimeoutError:
                return {"ok": True, "matched": False, "waited_s": self.clock() - started}
            if waiter["result"] is None:
                return {"ok": True, "matched": False, "waited_s": self.clock() - started}
            return {"ok": True, "matched": True, "notification": waiter["result"]}
        finally:
            self.pending.pop(identity, None)

    def close(self):
        for waiter in tuple(self.pending.values()):
            waiter["event"].set()
