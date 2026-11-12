import asyncio
import uuid


class FakePhone:
    def __init__(self, socket, auth):
        self.socket, self.auth = socket, auth

    @classmethod
    async def connect(cls, client, payload, *, headers=None, path="/ws", expected="auth.ok"):
        socket = await asyncio.wait_for(client.ws_connect(path, headers=headers), timeout=2)
        phone = cls(socket, None)
        await phone.send("system", "auth", payload)
        phone.auth = await phone.receive()
        assert phone.auth["type"] == expected
        return phone

    async def send(self, channel, kind, payload, identity=None):
        await asyncio.wait_for(self.socket.send_json({"channel": channel, "type": kind,
                               "id": identity or str(uuid.uuid4()), "payload": payload}), timeout=2)

    async def receive(self):
        return await asyncio.wait_for(self.socket.receive_json(), timeout=2)

    async def sync(self):
        identity = str(uuid.uuid4())
        await self.send("system", "ping", {"ts": 42}, identity)
        response = await self.receive()
        assert response["type"] == "pong" and response["id"] == identity

    async def status(self, payload=None):
        await self.send("bridge", "bridge.status", {} if payload is None else payload)
        await self.sync()

    async def respond(self, command, *, status=200, result=None):
        await self.send("bridge", "bridge.response", {"request_id": command["payload"]["request_id"],
                        "status": status, "result": {} if result is None else result})

    async def close(self):
        await asyncio.wait_for(self.socket.close(), timeout=2)


def session_payload(session):
    return {"session_token": session.token, "device_id": session.device_id}
