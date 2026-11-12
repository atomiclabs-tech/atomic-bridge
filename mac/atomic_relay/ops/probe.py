"""Bounded read-only loopback probes; response payloads are never logged."""

import json
import logging
import time
import urllib.error
import urllib.request

from atomic_relay.ops.secret import read_secret

logger = logging.getLogger("atomic_relay.ops")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Probe:
    def __init__(self, base_url, secret_file=None):
        self.base_url = base_url
        self.secret_file = secret_file
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def set_secret_file(self, path):
        self.secret_file = path

    def get(self, path, *, timeout=5):
        try:
            headers = {"Authorization": "Bearer " + read_secret(self.secret_file)} if self.secret_file else {}
            request = urllib.request.Request(self.base_url + path, headers=headers)
            with self.opener.open(request, timeout=timeout) as response:
                if response.status != 200:
                    raise ValueError(f"HTTP {response.status} for {path}")
                data = json.load(response)
            if not isinstance(data, dict):
                raise ValueError(f"non-object JSON for {path}")
            return data
        except (OSError, urllib.error.URLError, ValueError) as error:
            status = error.code if isinstance(error, urllib.error.HTTPError) else "unavailable"
            safe = RuntimeError(f"HTTP check failed at {path}; status={status}; response details withheld")
            logger.exception("component=ops operation=http failed path=%s status=%s", path, status,
                             exc_info=(RuntimeError, safe, error.__traceback__))
            raise


def healthy(data, version):
    return data.get("status") == "ok" and data.get("version") == version


def phone_connected(data):
    devices = data.get("devices")
    if not isinstance(devices, list) or any(not isinstance(item, dict) for item in devices):
        raise ValueError("invalid /bridge/devices shape")
    return any(
        item.get("connected") is True and item.get("phone_connected", True) is True
        and item.get("device_form_factor") == "phone"
        and isinstance(item.get("device"), dict)
        and item["device"].get("application_id") == "tech.atomiclabs.link"
        for item in devices
    )


def wait_for(probe, path, predicate, *, budget, clock=time.monotonic, sleep=time.sleep):
    """Poll only read-only status. A zero budget means one bounded verification call."""
    deadline = clock() + budget
    while True:
        remaining = deadline - clock()
        try:
            data = probe.get(path, timeout=min(5, max(0.001, remaining)) if budget else 5)
            if predicate(data):
                return
            raise ValueError(f"expected state not present at {path}")
        except (OSError, urllib.error.URLError, ValueError) as error:
            safe = RuntimeError("status check failed; response details withheld")
            logger.exception("component=ops operation=check expected state missing path=%s", path,
                             exc_info=(RuntimeError, safe, error.__traceback__))
            remaining = deadline - clock()
            if not budget or remaining <= 0:
                raise
            logger.warning("component=ops operation=poll retry path=%s remaining=%.3f", path, remaining)
            sleep(min(1, remaining))
