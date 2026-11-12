# Extracts plugin/tools/android_tool.py from upstream commit da782a8 without importing it.
"""Regenerate the exact 1.13.1 schema fixture from an explicit pinned source path."""

import argparse
import ast
import copy
import json
from pathlib import Path


def extract_schemas(source: str) -> dict:
    """Read only schema literals and the device-selector description constants."""
    wanted = {"_SCHEMAS", "_DEVICE_SELECTOR_DESCRIPTION", "_DEVICE_SELECTOR_PROPERTY"}
    dependencies = {"_DRAG_DEFAULT_DURATION_MS"}
    constants = {}
    for node in ast.parse(source).body:
        if not isinstance(node, ast.Assign) or len(node.targets) != 1:
            continue
        target = node.targets[0]
        if not isinstance(target, ast.Name) or target.id not in wanted | dependencies:
            continue
        expression = copy.deepcopy(node.value)

        class ReplaceConstants(ast.NodeTransformer):
            def visit_Name(self, name):
                if name.id not in constants:
                    raise ValueError(f"Unsupported schema reference: {name.id}")
                return ast.Constant(value=constants[name.id])

        expression = ReplaceConstants().visit(expression)
        constants[target.id] = ast.literal_eval(expression)
    if not wanted <= set(constants):
        raise ValueError("Pinned source is missing schema or device-selector declarations")
    schemas = constants["_SCHEMAS"]
    if len(schemas) != 35:
        raise ValueError("Pinned source must contain exactly 35 schemas")
    for name, schema in schemas.items():
        if name != "android_setup":
            properties = schema.setdefault("parameters", {"type": "object"}).setdefault("properties", {})
            properties.setdefault("device", copy.deepcopy(constants["_DEVICE_SELECTOR_PROPERTY"]))
    return schemas


def fixture_bytes(source: str) -> bytes:
    """Use one stable UTF-8 representation for a reproducible fixture."""
    return (json.dumps(extract_schemas(source), ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Pinned clone's plugin/tools/android_tool.py")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.write_bytes(fixture_bytes(args.source.read_text(encoding="utf-8")))


if __name__ == "__main__":
    main()
