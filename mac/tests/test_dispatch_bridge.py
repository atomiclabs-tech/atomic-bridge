import asyncio
import json

import pytest

from atomic_relay.bridge import dispatch
from bridge_cases import ready_registry


def test_existing_and_new_per_command_budgets_do_not_change_legacy_default(monkeypatch):
    async def check():
        registry, socket = ready_registry()
        dispatcher = dispatch.CommandDispatcher(registry)
        waits = []
        original = asyncio.wait_for
        async def observed(awaitable, *, timeout):
            waits.append(timeout)
            return await original(awaitable, timeout=timeout)
        monkeypatch.setattr(dispatch.asyncio, "wait_for", observed)
        async def respond(raw):
            payload = json.loads(raw)["payload"]
            dispatcher.response(socket, {"request_id": payload["request_id"], "status": 200, "result": {}})
        monkeypatch.setattr(socket, "send_str", respond)
        for path, budget in (("/ping", 30), ("/wait", 30), ("/call_audio_probe", 119), ("/files/pull", 290), ("/files/push", 290)):
            await dispatcher.command("POST", path, {}, {})
            assert waits[-2:] == [5, budget]
    asyncio.run(check())


def test_malformed_response_does_not_consume_a_valid_pending_request():
    async def check():
        registry, socket = ready_registry()
        dispatcher = dispatch.CommandDispatcher(registry)
        task = asyncio.create_task(dispatcher.command("GET", "/ping", {}, {}))
        command = await asyncio.wait_for(socket.sent.get(), timeout=2)
        identity = command["id"]
        for payload in ({}, {"request_id": identity, "status": True, "result": {}}, {"request_id": identity, "status": 200}):
            with pytest.raises(ValueError):
                dispatcher.response(socket, payload)
        dispatcher.response(socket, {"request_id": identity, "status": 200, "result": "unchanged"})
        assert (await task)["result"] == "unchanged"
    asyncio.run(check())


def test_send_timeout_is_a_single_failed_attempt_and_cleans_pending():
    async def check():
        registry, socket = ready_registry()
        dispatcher = dispatch.CommandDispatcher(registry, send_timeout=0.001)
        calls = []
        async def stalled(raw):
            calls.append(1)
            await asyncio.get_running_loop().create_future()
        socket.send_str = stalled
        with pytest.raises(dispatch.BridgeError) as error:
            await dispatcher.command("POST", "/tap", {}, {})
        assert error.value.status_code == 502 and isinstance(error.value.__cause__, TimeoutError)
        assert calls == [1] and dispatcher.pending == {}
    asyncio.run(check())
