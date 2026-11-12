"""Register Atomic phone tools using the public plugin context only."""

from . import registry
from .checks import check_fn


def register(ctx):
    for entry in registry.TOOL_ENTRIES:
        ctx.register_tool(
            name=entry.name,
            toolset="android",
            schema=entry.schema,
            handler=entry.handler,
            check_fn=None if entry.name == "android_setup" else check_fn,
        )
