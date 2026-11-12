# Adapted from plugin/tools/resolve_result.py at upstream commit da782a8.
"""Classify successful, missing and permission-denied resolver responses."""

from dataclasses import dataclass
from typing import Generic, TypeVar

T = TypeVar("T")


@dataclass(frozen=True)
class Found(Generic[T]):
    value: T


@dataclass(frozen=True)
class NotFound:
    detail: str | None = None


@dataclass(frozen=True)
class PermissionDenied:
    permission: str
    reason: str = ""


ResolveResult = Found[T] | NotFound | PermissionDenied


def _first_present(mapping, keys):
    for key in keys:
        value = mapping.get(key)
        if isinstance(value, str) and value:
            return value
    return None


def from_bridge_response(response, *, found_value=None):
    """Recognize canonical and legacy permission fields without rewriting them."""
    if _first_present(response, ("code", "error_code")) == "permission_denied":
        permission = _first_present(response, ("permission", "required_permission"))
        reason = response.get("error")
        return PermissionDenied(permission or "", reason if isinstance(reason, str) else "")
    if found_value is not None:
        return Found(found_value)
    detail = response.get("error")
    return NotFound(detail if isinstance(detail, str) else None)
