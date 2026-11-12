import asyncio

import pytest

from atomic_relay.web.runtime import RelayRuntime
from session_cases import configuration


def test_runtime_composes_isolated_services_and_checks_socket_failure(tmp_path):
    runtime = RelayRuntime(configuration(tmp_path))
    other = RelayRuntime(configuration(tmp_path))
    runtime.sessions.create_session("phone", "device", ttl_seconds=0)
    assert other.sessions.active_count() == 0
    assert runtime.authentication.sessions is runtime.sessions
    class Socket:
        closed = False
        async def close(self, **kwargs):
            raise OSError("synthetic close failure")
    runtime.clients[Socket()] = "synthetic-owner"
    with pytest.raises(OSError, match="synthetic close"):
        asyncio.run(runtime.close_session_sockets("synthetic-owner"))
