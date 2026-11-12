import asyncio
import logging

import pytest

from atomic_relay.bridge.dispatch import CommandDispatcher
from atomic_relay.bridge.registry import BridgeError
from bridge_cases import Socket, ready_registry


def test_commands_are_correlated_to_owner_socket_and_response_is_unchanged():
    async def check():
        registry, socket = ready_registry()
        dispatcher = CommandDispatcher(registry)
        task = asyncio.create_task(dispatcher.command("GET", "/ping", {"limit": "2"}, {"limit": "2"}))
        envelope = await asyncio.wait_for(socket.sent.get(), timeout=2)
        identity = envelope["payload"]["request_id"]
        assert envelope["id"] == identity and envelope["channel"] == "bridge" and envelope["type"] == "bridge.command"
        assert envelope["payload"] == {"request_id": identity, "method": "GET", "path": "/ping",
                                       "params": {"limit": "2"}, "body": {"limit": "2"}}
        payload = {"request_id": identity, "status": 418, "result": ["synthetic", {"success": False}]}
        dispatcher.response(Socket(), payload)
        assert not task.done()
        dispatcher.response(socket, payload)
        assert await asyncio.wait_for(task, timeout=2) is payload
        dispatcher.response(socket, payload)
        assert dispatcher.pending == {} and socket.sent.empty()
    asyncio.run(check())


def test_timeout_disconnect_cancel_and_shutdown_cleanup_pending():
    async def check():
        registry, socket = ready_registry()
        dispatcher = CommandDispatcher(registry, timeout=0.001)
        with pytest.raises(BridgeError) as error:
            await dispatcher.command("GET", "/ping", {}, {})
        assert error.value.status_code == 504 and dispatcher.pending == {}
        await socket.sent.get()
        dispatcher.timeout = 2
        for action in ("detach", "close", "cancel"):
            task = asyncio.create_task(dispatcher.command("GET", "/ping", {}, {}))
            await asyncio.wait_for(socket.sent.get(), timeout=2)
            if action == "detach":
                dispatcher.detach(socket)
            elif action == "close":
                dispatcher.close()
            else:
                task.cancel()
            if action == "cancel":
                with pytest.raises(asyncio.CancelledError):
                    await task
            else:
                with pytest.raises(BridgeError) as error:
                    await task
                assert error.value.status_code == 502
            assert dispatcher.pending == {}
    asyncio.run(check())


def test_send_failure_is_logged_with_private_exception_text_removed(caplog, monkeypatch):
    monkeypatch.setattr(logging.getLogger("atomic_relay"), "propagate", True)
    private = "synthetic-private-send-detail"
    async def check():
        registry, socket = ready_registry()
        async def fail(raw):
            raise OSError(private)
        monkeypatch.setattr(socket, "send_str", fail)
        dispatcher = CommandDispatcher(registry)
        with pytest.raises(BridgeError) as error:
            await dispatcher.command("POST", "/type", {}, {"text": private})
        assert error.value.status_code == 502 and error.value.__cause__ is not None
        assert dispatcher.pending == {}
    with caplog.at_level(logging.ERROR):
        asyncio.run(check())
    assert private not in caplog.text
    assert caplog.records[-1].exc_info and "operation=send" in caplog.text
