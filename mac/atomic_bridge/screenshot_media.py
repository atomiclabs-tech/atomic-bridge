# Adapted from plugin/tools/android_screenshot_media.py at upstream commit da782a8.
"""Resolve bounded PNG/JPEG screenshots without exposing media data to logs."""

import base64
import binascii
import re
import struct
from dataclasses import dataclass
from io import BytesIO

from .tool_support import ToolFailure

MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024
_TOKEN = re.compile(r"MEDIA:hermes-relay://([A-Za-z0-9_-]{16,128})\Z")


class ScreenshotMediaError(ToolFailure):
    def __init__(self, message, stage="decode", **fields):
        super().__init__({**fields, "error": message, "stage": stage})


@dataclass(frozen=True)
class Screenshot:
    data: bytes
    content_type: str
    marker: str | None
    width: int
    height: int


def _image_info(data):
    if data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) >= 24 and data[12:16] == b"IHDR":
        width, height = struct.unpack(">II", data[16:24])
        mime = "image/png"
    elif data.startswith(b"\xff\xd8\xff"):
        width, height = _jpeg_dimensions(data)
        mime = "image/jpeg"
    else:
        raise ScreenshotMediaError("Screenshot is not a PNG or JPEG image")
    if width <= 0 or height <= 0:
        raise ScreenshotMediaError("Screenshot has invalid image dimensions")
    return mime, width, height


def _jpeg_dimensions(data):
    offset = 2
    while offset < len(data):
        if data[offset] != 0xFF:
            break
        while offset < len(data) and data[offset] == 0xFF:
            offset += 1
        if offset >= len(data):
            break
        marker = data[offset]
        offset += 1
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            continue
        if marker in (0xD9, 0xDA) or offset + 2 > len(data):
            break
        length = int.from_bytes(data[offset:offset + 2], "big")
        if length < 2 or offset + length > len(data):
            break
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            if length < 8:
                break
            height, width = struct.unpack(">HH", data[offset + 3:offset + 7])
            return width, height
        offset += length
    raise ScreenshotMediaError("Screenshot JPEG has no valid image dimensions")


def _fetch(marker, client):
    match = _TOKEN.fullmatch(marker) if isinstance(marker, str) else None
    if match is None:
        raise ScreenshotMediaError("Invalid screenshot media token")
    with BytesIO() as sink:
        response = client.download(f"/media/{match.group(1)}", sink, max_bytes=MAX_SCREENSHOT_BYTES)
        if response.status != 200 or response.value.get("error"):
            value = response.value
            message = value.get("error", "Screenshot media fetch failed")
            if response.status is not None and f"{response.status}" not in message:
                message = f"HTTP {response.status}: {message}"
            raise ScreenshotMediaError(message, "fetch", **{k: v for k, v in value.items() if k != "error"})
        data = sink.getvalue()
    mime = response.value.get("content_type")
    if mime not in ("image/png", "image/jpeg"):
        raise ScreenshotMediaError("Screenshot media has an unsupported content type", "fetch")
    if len(data) > MAX_SCREENSHOT_BYTES:
        raise ScreenshotMediaError(f"Screenshot exceeds byte limit ({MAX_SCREENSHOT_BYTES} bytes)", "fetch")
    return data, mime


def resolve_screenshot(raw, client):
    """Return verified bytes, image dimensions and the original media marker."""
    if not isinstance(raw, dict):
        raise ScreenshotMediaError("Invalid screenshot response")
    result = raw.get("data", raw)
    if not isinstance(result, dict):
        raise ScreenshotMediaError("Invalid screenshot response")
    marker = result.get("media")
    if marker is not None:
        data, declared = _fetch(marker, client)
    else:
        encoded = result.get("image")
        if not isinstance(encoded, str) or not encoded:
            raise ScreenshotMediaError("No image data returned")
        if len(encoded) > (MAX_SCREENSHOT_BYTES + 2) // 3 * 4:
            raise ScreenshotMediaError(f"Screenshot exceeds byte limit ({MAX_SCREENSHOT_BYTES} bytes)")
        try:
            data = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise ScreenshotMediaError("Invalid screenshot image data") from exc
        if len(data) > MAX_SCREENSHOT_BYTES:
            raise ScreenshotMediaError(f"Screenshot exceeds byte limit ({MAX_SCREENSHOT_BYTES} bytes)")
        declared = None
    mime, width, height = _image_info(data)
    if declared is not None and mime != declared:
        raise ScreenshotMediaError("Screenshot media type mismatch")
    return Screenshot(data, mime, marker, width, height)
