# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Phone functions and native media sharing commands."""

from .attachments import normalize_attachments
from .result import json_result
from .tool_support import RelayClient, ToolInputError, call, optional, required, selected, tool_handler


@tool_handler
def android_location(args: dict, **kwargs):
    return call("GET", "/location", args)


@tool_handler
def android_search_contacts(args: dict, **kwargs):
    return call("POST", "/search_contacts", args, {
        "query": required(args, "query"), "limit": args.get("limit", 20),
    })


@tool_handler
def android_call(args: dict, **kwargs):
    return call("POST", "/call", args, {"number": required(args, "number")})


@tool_handler
def android_send_sms(args: dict, **kwargs):
    return call("POST", "/send_sms", args, {"to": required(args, "to"), "body": required(args, "body")})


@tool_handler
def android_return_to_hermes(args: dict, **kwargs):
    return call("POST", "/return_to_hermes", args)


@tool_handler
def android_share_media(args: dict, **kwargs):
    client = RelayClient()
    items = normalize_attachments(args, client)
    text = args.get("text")
    if not items and not (text or "").strip():
        raise ToolInputError("Provide an attachment or text")
    values = optional(args, ("text", "title", "package"), {"attachments": items})
    return json_result(client.request("POST", "/share_media", selected(args, values)).value)


@tool_handler
def android_send_mms(args: dict, **kwargs):
    recipient = required(args, "to")
    if not isinstance(recipient, str) or not recipient.strip():
        raise ToolInputError("Provide a recipient phone number")
    client = RelayClient()
    items = normalize_attachments(args, client)
    body = args.get("body", "")
    if not items and not (body or "").strip():
        raise ToolInputError("Provide an attachment or body")
    values = optional(args, ("package",), {"to": recipient, "body": body, "attachments": items})
    return json_result(client.request("POST", "/send_mms", selected(args, values)).value)


HANDLERS = {function.__name__: function for function in (
    android_location, android_search_contacts, android_call, android_send_sms,
    android_return_to_hermes, android_share_media, android_send_mms,
)}
