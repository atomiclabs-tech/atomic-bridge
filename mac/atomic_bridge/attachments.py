# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Normalize share attachments and register host files through the relay client."""

import mimetypes
from pathlib import Path

from .tool_support import ToolFailure, ToolInputError, logger


def _strings(value):
    if value is None:
        return []
    values = [value] if isinstance(value, str) else value
    if not isinstance(values, (list, tuple)) or any(not isinstance(item, str) or not item.strip() for item in values):
        raise ToolInputError("Attachment paths and media tokens must be nonempty strings")
    return values


def _marker(value):
    value = value.strip()
    if value.startswith("MEDIA:"):
        return value
    if value.startswith("hermes-relay://"):
        return "MEDIA:" + value
    return "MEDIA:hermes-relay://" + value


def _path_attachment(client, path, content_type, file_name):
    source = Path(path).expanduser()
    if not source.is_file():
        raise ToolInputError("Attachment path must identify an existing file")
    guessed, _ = mimetypes.guess_type(str(source))
    resolved_type = content_type.strip() if content_type and content_type.strip() else guessed
    if not resolved_type:
        # Opaque file bytes intentionally use the protocol's generic MIME type.
        logger.warning("component=attachments operation=register unknown MIME type; using application/octet-stream")
        resolved_type = "application/octet-stream"
    resolved_name = file_name or source.name
    response = client.request("POST", "/media/register", {
        "path": str(source), "content_type": resolved_type, "file_name": resolved_name,
        "sensitive": False, "owned_file": False,
    }, timeout=5)
    value = response.value
    if response.status != 200 or value.get("ok") is not True or not isinstance(value.get("token"), str) or not value["token"]:
        raise ToolFailure({**value, "error": value.get("error", "Media registration returned no usable token")})
    return {"media": _marker(value["token"]), "content_type": resolved_type, "file_name": resolved_name}


def _token_attachment(token, content_type, file_name):
    item = {"media": _marker(token)}
    if content_type:
        item["content_type"] = content_type
    if file_name:
        item["file_name"] = file_name
    return item


def normalize_attachments(args, client):
    """Preserve the upstream attachment order and canonical outbound keys."""
    content_type, file_name = args.get("content_type"), args.get("file_name")
    attachments = args.get("attachments")
    if attachments is None:
        attachments = []
    if not isinstance(attachments, list):
        raise ToolInputError("attachments must be a list")
    normalized = []
    for raw in attachments:
        if not isinstance(raw, dict):
            raise ToolInputError("Each attachment must be an object")
        item_type = raw.get("content_type") or raw.get("mime_type") or content_type
        item_name = raw.get("file_name") or raw.get("filename") or file_name
        if isinstance(raw.get("path"), str) and raw["path"].strip():
            normalized.append(_path_attachment(client, raw["path"], item_type, item_name))
        else:
            token = raw.get("media") or raw.get("media_token") or raw.get("token")
            if not isinstance(token, str) or not token.strip():
                raise ToolInputError("Each attachment needs a path or media token")
            normalized.append(_token_attachment(token, item_type, item_name))
    for path in _strings(args.get("path")) + _strings(args.get("paths")):
        normalized.append(_path_attachment(client, path, content_type, file_name))
    for token in _strings(args.get("media")) + _strings(args.get("media_token")) + _strings(args.get("media_tokens")):
        normalized.append(_token_attachment(token, content_type, file_name))
    return normalized
