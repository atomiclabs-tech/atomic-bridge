"""List, pull and push Atomic files through the accepted relay transports."""

import re

from .client import RelayClient
from .filestream import FileTransferError, inspect_upload, plain_name, pull_to_path
from .result import json_result
from .tool_support import ToolInputError, optional, required, selected, tool_handler

_TOKEN = re.compile(r"[A-Za-z0-9_-]{16,128}\Z")


def _failed(response):
    return response.status != 200 or "error" in response.value or response.value.get("success") is False


def _error(response, stage):
    value = dict(response.value)
    if value.get("success") is not False:
        value["stage"] = stage
        if response.status is not None:
            value["status"] = response.status
        if "error" not in value:
            value["error"] = "File request failed"
    if stage == "register" and response.status == 403:
        value["hint"] = (
            "The relay registers local files only under its allowed roots "
            "(ATOMIC_RELAY_MEDIA_ALLOWED_ROOTS, default ~/.hermes/workspace). "
            "Copy the file under one of them and retry."
        )
    return json_result(value)


@tool_handler
def android_list_files(args: dict, **kwargs):
    limit = args.get("limit", 200)
    if isinstance(limit, bool) or not isinstance(limit, int) or limit < 1:
        raise ToolInputError("limit must be a positive integer")
    values = optional(args, ("path",), {"limit": limit})
    return json_result(RelayClient().request("GET", "/files", selected(args, values)).value)


@tool_handler
def android_pull_file(args: dict, **kwargs):
    client = RelayClient()
    response = client.request("POST", "/files/pull", selected(args, {"path": required(args, "path")}), timeout=300)
    if _failed(response):
        return _error(response, "command")
    return json_result(pull_to_path(client, response.value, args.get("dest")))


@tool_handler
def android_push_file(args: dict, **kwargs):
    upload = inspect_upload(required(args, "path"))
    name = plain_name(args.get("name", upload.name))
    overwrite = args.get("overwrite", False)
    if not isinstance(overwrite, bool):
        raise ToolInputError("overwrite must be a boolean")
    client = RelayClient()
    registration = client.request("POST", "/media/register", {
        "path": str(upload.path), "content_type": upload.content_type, "file_name": upload.name,
    }, timeout=300)
    if _failed(registration):
        return _error(registration, "register")
    value = registration.value
    token = value.get("token")
    if value.get("ok") is not True or not isinstance(token, str) or not _TOKEN.fullmatch(token):
        raise FileTransferError("Media registration returned no valid token", "register")
    if type(value.get("size")) is not int or value["size"] != upload.size or value.get("sha256") != upload.sha256:
        raise FileTransferError("Registered file size or SHA-256 does not match the local file", "register")
    values = optional(args, ("dir",), {
        "media": "MEDIA:hermes-relay://" + token, "name": name, "size": upload.size,
        "sha256": upload.sha256, "overwrite": overwrite,
    })
    return json_result(client.request("POST", "/files/push", selected(args, values), timeout=300).value)


HANDLERS = {"android_list_files": android_list_files, "android_pull_file": android_pull_file,
            "android_push_file": android_push_file}
