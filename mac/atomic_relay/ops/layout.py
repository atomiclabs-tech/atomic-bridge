"""Installation locations derived from an explicitly supplied home directory."""

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Layout:
    home: Path
    uid: int

    @property
    def service(self):
        return self.home / "services/atomic-bridge"

    def plugin(self, name):
        return self.home / ".hermes/plugins" / name

    def plist(self, name):
        return self.home / "Library/LaunchAgents" / f"{name}.plist"

    def label(self, name):
        return f"gui/{self.uid}/{name}"
