# Adapted from plugin/tools/android_tool.py at upstream commit da782a8.
"""Standard-library, proxy-free loopback transport with admin authentication."""

import hashlib
import json
import logging
import math
import os
import threading
from dataclasses import dataclass
from http.client import HTTPException
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

from .result import json_result

logger = logging.getLogger("atomic_bridge")


@dataclass(frozen=True)
class RelayResponse:
    """HTTP status and JSON result or download metadata, including transport errors."""

    status: int | None
    value: dict


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # A redirected request must never forward the bearer credential.
        return None


class _DownloadValidationError(ValueError):
    """A reason built only from fixed text and a validated byte limit."""


class _ConfigurationError(ValueError):
    """A configuration failure containing fixed explanatory text only."""


class RelayClient:
    """Perform one command; refresh rejected credentials for the next explicit call."""

    def __init__(self, *, environ=None, opener=None):
        self._env = os.environ if environ is None else environ
        self._opener = opener if opener is not None else build_opener(ProxyHandler({}), _NoRedirect())
        self._cached = None
        self._lock = threading.Lock()

    def _configuration(self):
        url = self._env.get("ANDROID_BRIDGE_URL", "http://127.0.0.1:8767").rstrip("/")
        try:
            parts = urlsplit(url)
            parts.port  # Validate the configured port before any credential read or HTTP.
        except ValueError as exc:
            raise _ConfigurationError("ANDROID_BRIDGE_URL must be a valid http loopback URL") from exc
        if (parts.scheme != "http" or parts.hostname not in ("127.0.0.1", "::1", "localhost")
                or parts.username is not None or parts.password is not None
                or any(char.isspace() or ord(char) < 32 for char in url)):
            raise _ConfigurationError("ANDROID_BRIDGE_URL must be an http loopback URL without credentials")
        if "?" in url or "#" in url:
            raise _ConfigurationError("ANDROID_BRIDGE_URL must not contain a query or fragment")
        try:
            timeout = float(self._env.get("ANDROID_BRIDGE_TIMEOUT", "30"))
        except ValueError as exc:
            raise _ConfigurationError("ANDROID_BRIDGE_TIMEOUT must be positive and finite") from exc
        if not math.isfinite(timeout) or timeout <= 0:
            raise _ConfigurationError("ANDROID_BRIDGE_TIMEOUT must be positive and finite")
        return url, timeout

    def _secret(self, refresh=False) -> str:
        with self._lock:
            if not refresh and self._cached is not None:
                return self._cached
            path = Path(self._env.get(
                "ATOMIC_BRIDGE_ADMIN_SECRET_FILE", "~/services/atomic-bridge/admin.secret"
            )).expanduser()
            try:
                secret = path.read_text(encoding="utf-8").strip()
            except FileNotFoundError as exc:
                raise _ConfigurationError("Admin secret file is missing or empty; run tools/install-mac.sh or set ATOMIC_BRIDGE_ADMIN_SECRET_FILE") from exc
            except (OSError, UnicodeError) as exc:
                raise _ConfigurationError("Admin secret file could not be read; run tools/install-mac.sh or set ATOMIC_BRIDGE_ADMIN_SECRET_FILE") from exc
            if not secret:
                raise _ConfigurationError("Admin secret file is missing or empty; run tools/install-mac.sh or set ATOMIC_BRIDGE_ADMIN_SECRET_FILE")
            self._cached = secret
            return secret

    def _refresh_denied(self, result):
        if result.status in (401, 403):
            try:
                self._secret(refresh=True)
            except (OSError, ValueError) as exc:
                self._log_failure("credential-refresh", exc)
                message = result.value["error"] + f"; credential refresh failed: {exc}"
                return RelayResponse(result.status, {"error": message, "status": result.status})
        return result

    @staticmethod
    def _log_failure(method: str, exc: BaseException):
        # Preserve the failing frames without recording URLs, credentials, or response bodies.
        safe = RuntimeError(f"{type(exc).__name__} during relay {method}")
        logger.error("component=client operation=%s failed", method,
                     exc_info=(RuntimeError, safe, exc.__traceback__))

    @staticmethod
    def _payload(raw: bytes, secret: str) -> dict:
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError("Relay response must be a JSON object")
        # An erroneous server response must not echo our bearer into a tool result.
        return json.loads(json.dumps(value).replace(json.dumps(secret)[1:-1], "[redacted]"))

    def _send(self, method, url, arguments, timeout, secret):
        headers = {"Accept": "application/json", "Authorization": f"Bearer {secret}"}
        body = None
        if method == "GET" and arguments:
            url += ("&" if "?" in url else "?") + urlencode(arguments)
        elif method == "POST":
            body = json.dumps(arguments).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = Request(url, data=body, headers=headers, method=method)
        try:
            with self._opener.open(request, timeout=timeout) as response:
                status = response.status
                value = self._payload(response.read(), secret)
        except HTTPError as exc:
            self._log_failure(method, exc)
            raw = ""
            try:
                with exc:
                    raw = exc.read().decode("utf-8", errors="replace")
            except (HTTPException, OSError) as error:
                self._log_failure(f"{method}-error-body", error)
            message = raw or str(exc.reason)
            if raw:
                try:
                    value = json.loads(raw)
                except json.JSONDecodeError as error:
                    self._log_failure(f"{method}-error-body-json", error)
                else:
                    if isinstance(value, dict) and isinstance(value.get("error"), str) and value["error"]:
                        message = value["error"]
            message = message.replace(secret, "[redacted]")
            message = message.replace(json.dumps(secret)[1:-1], "[redacted]")
            return RelayResponse(exc.code, {"error": f"HTTP {exc.code}: {message}", "status": exc.code})
        if not 200 <= status < 300:
            raise ValueError(f"Relay returned unexpected HTTP status {status}")
        return RelayResponse(status, value)

    def request(self, method: str, path: str, args=None, *, timeout=None) -> RelayResponse:
        """Return the HTTP status and JSON object; failures are structured objects."""
        method = method.upper()
        try:
            if method not in ("GET", "POST"):
                raise ValueError("Relay method must be GET or POST")
            url, budget = self._transport_target(path, timeout)
            arguments = dict(args or {})
            device = arguments.pop("device", None)
            if device is not None:
                if not isinstance(device, str) or not device.strip():
                    raise ValueError("device must be a nonempty string")
                arguments["device"] = device.strip()
            return self._refresh_denied(self._send(method, url, arguments, budget, self._secret()))
        except (HTTPException, OSError, URLError, ValueError, UnicodeError, TypeError) as exc:
            return self._transport_failure(method, exc)

    def _transport_target(self, path, timeout):
        parts = urlsplit(path)
        if not path.startswith("/") or path.startswith("//") or parts.scheme or parts.netloc or parts.fragment:
            raise _ConfigurationError("Relay path must be relative to the configured server")
        url, configured_timeout = self._configuration()
        budget = configured_timeout if timeout is None else float(timeout)
        if not math.isfinite(budget) or budget <= 0:
            raise _ConfigurationError("Request timeout must be positive and finite")
        return url + path, budget

    def _transport_failure(self, operation, exc, status=None):
        self._log_failure(operation, exc)
        if isinstance(exc, _ConfigurationError):
            return RelayResponse(status, {"error": str(exc)})
        reason = str(exc) if type(exc) is _DownloadValidationError else type(exc).__name__
        value = {"error": f"Relay {operation} failed: {reason}"}
        if status is not None:
            value["status"] = status
        return RelayResponse(status, value)

    @staticmethod
    def _download_metadata(response, max_bytes):
        content_type = response.headers.get("Content-Type")
        if not isinstance(content_type, str) or not content_type.strip():
            raise _DownloadValidationError("Download response is missing its content type")
        content_type = content_type.split(";", 1)[0].strip().lower()
        media_parts = content_type.split("/")
        if len(media_parts) != 2 or not all(media_parts) or any(char.isspace() for char in content_type):
            raise _DownloadValidationError("Download response has an invalid content type")
        length = response.headers.get("Content-Length")
        if length is not None:
            if not isinstance(length, str) or not length.isascii() or not length.isdecimal():
                raise _DownloadValidationError("Download response has an invalid content length")
            length = int(length)
            if length > max_bytes:
                raise _DownloadValidationError(f"Download response exceeds byte limit ({max_bytes} bytes)")
        return content_type, length

    @staticmethod
    def _copy_download(response, sink, max_bytes, length):
        digest = hashlib.sha256()
        size = 0
        while True:
            chunk = response.read(min(64 * 1024, max_bytes - size + 1))
            if not isinstance(chunk, bytes):
                raise _DownloadValidationError("Download response must contain binary chunks")
            if not chunk:
                break
            if size + len(chunk) > max_bytes:
                raise _DownloadValidationError(f"Download response exceeds byte limit ({max_bytes} bytes)")
            written = sink.write(chunk)
            if not isinstance(written, int) or isinstance(written, bool) or written != len(chunk):
                raise OSError("Download sink did not write the complete chunk")
            digest.update(chunk)
            size += written
        if length is not None and length != size:
            raise _DownloadValidationError("Download response does not match its content length")
        return size, digest.hexdigest()

    def download(self, path: str, sink, *, max_bytes, timeout=None) -> RelayResponse:
        """Stream authenticated bytes to a caller-owned sink; never retry or clean it up.

        Success includes size, SHA-256 and normalized content_type. Failure can leave
        partial bytes in the sink; callers discard them and own sink cleanup.
        """
        status = None
        try:
            if isinstance(max_bytes, bool) or not isinstance(max_bytes, int) or max_bytes <= 0:
                raise ValueError("Download byte limit must be a positive integer")
            url, budget = self._transport_target(path, timeout)
            request = Request(url, headers={"Authorization": f"Bearer {self._secret()}"}, method="GET")
            with self._opener.open(request, timeout=budget) as response:
                status = response.status
                if not 200 <= status < 300:
                    raise ValueError("Download returned an unsuccessful HTTP status")
                content_type, length = self._download_metadata(response, max_bytes)
                size, digest = self._copy_download(response, sink, max_bytes, length)
            return RelayResponse(status, {"size": size, "sha256": digest, "content_type": content_type})
        except HTTPError as exc:
            with exc:
                return self._refresh_denied(self._transport_failure("download", exc, exc.code))
        except (HTTPException, OSError, URLError, ValueError, TypeError, BufferError) as exc:
            return self._transport_failure("download", exc, status)

    def get(self, path: str, args=None, *, timeout=None) -> str:
        """Return a GET result in the supported JSON-string tool format."""
        return json_result(self.request("GET", path, args, timeout=timeout).value)

    def post(self, path: str, args=None, *, timeout=None) -> str:
        """Return a POST result in the supported JSON-string tool format."""
        return json_result(self.request("POST", path, args, timeout=timeout).value)
