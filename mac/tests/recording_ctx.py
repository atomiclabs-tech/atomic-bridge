"""Minimal recording implementation of the documented plugin context."""


class RecordingContext:
    def __init__(self):
        self.tools = []

    def register_tool(self, *, name, toolset, schema, handler, check_fn=None):
        if not isinstance(schema.get("parameters"), dict):
            raise TypeError("Tool schema parameters must be a dict")
        if any(tool["name"] == name for tool in self.tools):
            raise ValueError("Duplicate tool registration")
        self.tools.append({
            "name": name, "toolset": toolset, "schema": schema,
            "handler": handler, "check_fn": check_fn,
        })
