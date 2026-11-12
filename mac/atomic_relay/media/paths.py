# Ported from plugin/relay/media.py at upstream commit da782a8.
"""Resolve allowed media paths while excluding credential stores."""

import stat
from pathlib import Path


class MediaError(ValueError):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def under(path, roots):
    return any(path == root or root in path.parents for root in roots)


class MediaPaths:
    def __init__(self, roots, media_dir, max_bytes, *, sessions_file=None, home=None):
        self.media_dir = Path(media_dir).resolve()
        self.roots = tuple(Path(root).resolve() for root in roots)
        self.max_bytes = max_bytes
        actual_home = Path.home().resolve()
        home = actual_home if home is None else Path(home).resolve()
        system = ("/etc", "/proc", "/sys", "/dev", "/root", "/boot", "/var/log", "/var/lib", "/var/run")
        credentials = (".ssh", ".aws", ".gnupg", ".kube", ".docker", ".config", ".azure", ".gcloud", "Library/Keychains",
                       ".netrc", ".git-credentials", ".npmrc", ".pypirc", ".bundle", ".gradle/gradle.properties", "Library")
        stores = (".env", "auth.json", "auth.lock", "credentials", "config.yaml", ".anthropic_oauth.json",
                  "google_token.json", "google_oauth_pending.json", "auth/google_oauth.json", "webhook_subscriptions.json",
                  "cache/bws_cache.json", "hermes-relay-qr-secret", "hermes-relay-sessions.json", "pairing", "mcp-tokens")
        # A system-home alias must not turn the entire user's home into a credential directory.
        self.denied = [Path(path).resolve() for path in system if Path(path).resolve() not in {home, actual_home}]
        self.denied.extend((home / name).resolve() for name in credentials)
        self.denied.extend((home / ".hermes" / name).resolve() for name in stores)
        self.denied.append((home / ".hermes-relay" / "config.yaml").resolve())
        if sessions_file is not None:
            self.denied.append(Path(sessions_file).resolve())

    def validate(self, path, *, upload=False):
        if not isinstance(path, (str, Path)) or not Path(path).is_absolute():
            raise MediaError("media path must be absolute")
        resolved = Path(path).resolve()
        if under(resolved, self.denied):
            raise MediaError("media path is a protected credential or system location", 403)
        roots = (*self.roots, self.media_dir) if upload else self.roots
        if not under(resolved, roots):
            raise MediaError("media path is outside allowed roots", 403)
        info = resolved.stat()
        if not stat.S_ISREG(info.st_mode):
            raise MediaError("media path must be a regular file")
        if info.st_size > self.max_bytes:
            raise MediaError("media file exceeds the size limit", 413)
        return resolved

    def owned(self, path):
        return path.parent == self.media_dir and path.name.startswith("atomic-upload-")
