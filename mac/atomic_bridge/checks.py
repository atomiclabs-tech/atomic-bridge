# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Keep tools visible across phone reconnects and relay outages.

The host freezes a session's tool list when the agent is built. /reload-mcp,
/new, or context compaction rebuilds that list.
"""

import logging

from .client import RelayClient

logger = logging.getLogger("atomic_bridge")


def check_fn() -> bool:
    """Hide tools only after an explicit unsupported-device response."""
    try:
        response = RelayClient().request("GET", "/bridge/status", timeout=2)
        if response.status != 200:
            logger.warning("component=checks operation=status unavailable; keeping phone tools visible")
            return True
        support = response.value.get("bridge")
        return not (isinstance(support, dict) and support.get("device_control_supported") is False)
    except Exception as exc:
        # This entry point must never hide tools because a transport raised unexpectedly.
        safe = RuntimeError(f"{type(exc).__name__} during availability check")
        logger.exception("component=checks operation=status failed", exc_info=(RuntimeError, safe, exc.__traceback__))
        logger.warning("component=checks operation=status failed; keeping phone tools visible")
        return True
