"""Shared test isolation.

Some tools configure the relay logger when loaded (handlers replaced, propagate off). Reset it
to the plain default before every test and restore it afterwards, so caplog-based tests see the
same logger no matter which tests ran first.
"""

import logging

import pytest


@pytest.fixture(autouse=True)
def _pristine_relay_logger():
    relay = logging.getLogger("atomic_relay")
    access = logging.getLogger("aiohttp.access")
    saved = (relay.handlers[:], relay.level, relay.propagate, relay.disabled, access.disabled)
    relay.handlers, relay.level, relay.propagate, relay.disabled = [], logging.NOTSET, True, False
    yield
    relay.handlers, relay.level, relay.propagate, relay.disabled, access.disabled = saved


@pytest.fixture(autouse=True)
def _keep_plugin_modules():
    """tools/call_tool.py reloads the plugin under its real name; put the originals back."""
    import sys

    saved = {name: module for name, module in sys.modules.items() if name.split(".")[0] == "atomic_bridge"}
    yield
    for name, module in saved.items():
        sys.modules[name] = module


@pytest.fixture(autouse=True)
def _synthetic_plugin_admin_secret(tmp_path_factory, monkeypatch):
    path = tmp_path_factory.mktemp("plugin-admin") / "admin.secret"
    path.write_text("synthetic-" + "auth", encoding="utf-8")
    path.chmod(0o600)
    monkeypatch.setenv("ATOMIC_BRIDGE_ADMIN_SECRET_FILE", str(path))
