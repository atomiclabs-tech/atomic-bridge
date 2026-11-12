#!/usr/bin/env python3
"""Call a local Atomic plugin tool; save image results instead of printing bytes."""

import argparse
import base64
import binascii
import importlib.util
import json
import logging
import re
import sys
import tempfile
from pathlib import Path

logger = logging.getLogger("atomic_bridge")
_MEDIA = re.compile(r"MEDIA:hermes-relay://[A-Za-z0-9_-]+")
_IMAGE_SUFFIXES = {"image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp", "image/gif": ".gif"}


class RecordingContext:
    def __init__(self):
        self.handlers = {}

    def register_tool(self, *, name, toolset, schema, handler, check_fn=None):
        if name in self.handlers:
            raise ValueError("Duplicate tool registration")
        self.handlers[name] = handler

    def dispatch_tool(self, name, args, **kwargs):
        if name not in self.handlers:
            raise ValueError("Unknown Atomic tool name")
        return self.handlers[name](args, **kwargs)


def load_context():
    """Load the checkout plugin using only its public register(ctx) entry point."""
    package = Path(__file__).resolve().parents[1] / "mac" / "atomic_bridge"
    entry = package / "__init__.py"
    if not entry.is_file():
        raise ValueError("Local Atomic plugin is missing")
    spec = importlib.util.spec_from_file_location("atomic_bridge", entry, submodule_search_locations=[str(package)])
    if spec is None or spec.loader is None:
        raise ValueError("Local Atomic plugin could not be loaded")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    ctx = RecordingContext()
    module.register(ctx)
    return ctx


def save_images(value, directory):
    """Persist data-URL images and return paths without private media summaries."""
    content = value.get("content")
    if not isinstance(content, list):
        raise ValueError("Image result has no content list")
    images = []
    try:
        for block in content:
            if not isinstance(block, dict):
                raise ValueError("Image result contains an invalid content block")
            if block.get("type") != "image_url":
                continue
            image = block.get("image_url")
            url = image.get("url") if isinstance(image, dict) else None
            if not isinstance(url, str):
                raise ValueError("Image result has no data URL")
            prefix, separator, encoded = url.partition(",")
            mime = prefix.removeprefix("data:").removesuffix(";base64")
            if not separator or prefix != f"data:{mime};base64" or mime not in _IMAGE_SUFFIXES:
                raise ValueError("Image result has an unsupported data URL")
            try:
                data = base64.b64decode(encoded, validate=True)
            except (binascii.Error, ValueError) as error:
                raise ValueError("Image result contains invalid base64") from error
            if not data:
                raise ValueError("Image result is empty")
            with tempfile.NamedTemporaryFile(prefix="atomic-image-", suffix=_IMAGE_SUFFIXES[mime], dir=directory, delete=False) as output:
                images.append({"path": str(Path(output.name).resolve()), "content_type": mime})
                written = output.write(data)
                if written != len(data):
                    raise OSError("Image file write was incomplete")
        if not images:
            raise ValueError("Image result contains no images")
        return {"images": images}
    except (OSError, ValueError, TypeError):
        for image in images:
            Path(image["path"]).unlink(missing_ok=True)
        raise


def format_result(raw, image_dir):
    value = json.loads(raw) if isinstance(raw, str) else raw
    if not isinstance(value, dict):
        raise ValueError("Tool result must be a JSON object or image envelope")
    if value.get("_multimodal") is True:
        value = save_images(value, image_dir)
    failed = bool(value.get("error")) or value.get("success") is False or value.get("ok") is False
    return _MEDIA.sub("[private media]", json.dumps(value, ensure_ascii=False)), int(failed)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name", help="registered android_* tool name")
    parser.add_argument("args", help="JSON object of tool arguments")
    parser.add_argument("--image-dir", type=Path, help="existing directory for image files (default: system temporary directory)")
    options = parser.parse_args(argv)
    try:
        try:
            arguments = json.loads(options.args)
        except json.JSONDecodeError as error:
            raise ValueError("Tool arguments must be valid JSON") from error
        if not isinstance(arguments, dict):
            raise ValueError("Tool arguments must be a JSON object")
        result = load_context().dispatch_tool(options.name, arguments)
        rendered, status = format_result(result, options.image_dir)
    except Exception as error:
        safe = RuntimeError(f"{type(error).__name__} during call-tool")
        logger.exception("component=call_tool operation=invoke failed", exc_info=(RuntimeError, safe, error.__traceback__))
        message = str(error) if type(error) is ValueError else f"Tool call failed: {type(error).__name__}"
        rendered, status = json.dumps({"error": message}), 1
    print(rendered)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
