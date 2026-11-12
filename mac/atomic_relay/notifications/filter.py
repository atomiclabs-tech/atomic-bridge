"""Pure package policy applied before notification buffering and publication."""

import re
from dataclasses import dataclass


def validate_patterns(patterns: tuple[str, ...]) -> None:
    for pattern in patterns:
        if not isinstance(pattern, str) or re.fullmatch(r"[A-Za-z0-9_.]+(?:\.\*)?", pattern) is None:
            raise ValueError("invalid notification package pattern")


def _matches(package: str, patterns: tuple[str, ...]) -> bool:
    return any(package.startswith(pattern[:-1]) if pattern.endswith(".*") else package == pattern for pattern in patterns)


@dataclass(frozen=True)
class PackageFilter:
    allow: tuple[str, ...] = ()
    deny: tuple[str, ...] = ()

    def __post_init__(self):
        validate_patterns(self.allow)
        validate_patterns(self.deny)

    def keeps(self, package: str) -> bool:
        if not isinstance(package, str):
            raise ValueError("notification package must be a string")
        return not _matches(package, self.deny) and (not self.allow or _matches(package, self.allow))
