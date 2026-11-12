import asyncio
from types import SimpleNamespace

from atomic_relay.bridge.registry import DeviceRegistry


class Socket:
    def __init__(self):
        self.closed = False
        self.sent = asyncio.Queue()

    async def send_str(self, raw):
        import json
        await self.sent.put(json.loads(raw))


def session(identity="device", name="Pixel Synthetic", form="phone"):
    return SimpleNamespace(device_id=identity, device_name=name, token="synthetic-session-fixture",
                           client_surface="android", device_form_factor=form)


def ready_registry(*, clock=lambda: 0):
    registry, socket = DeviceRegistry(clock=clock), Socket()
    registry.update(socket, {}, session())
    return registry, socket
