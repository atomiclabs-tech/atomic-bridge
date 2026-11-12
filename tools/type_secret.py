#!/usr/bin/env python3
"""Type a private vault field into the phone's focused field.

Configure BW (default: bw on PATH), BW_SESSION or ATOMIC_BW_SESSION_CMD,
and optionally BITWARDENCLI_APPDATA_DIR in the caller's environment.
Usage: tools/py.sh ../tools/type_secret.py ITEM [--field password|username|totp]
       [--no-clear] [--list] (list matching item names without login fields).
       tools/py.sh ../tools/type_secret.py --check (no session required).
"""

import argparse
import json
import logging
import os
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from type_secret_vault import NO_SESSION, SecretError, get_field, list_items, lock, session, status, sync, version  # noqa: E402

logger = logging.getLogger("atomic_bridge")


def _tool_result(raw, *private):
    value = json.loads(raw) if isinstance(raw, str) else raw
    if not isinstance(value, dict):
        raise SecretError("Phone tool returned an invalid result")
    rendered = json.dumps(value, ensure_ascii=False)
    for secret in private:
        rendered = rendered.replace(json.dumps(secret, ensure_ascii=False)[1:-1], "***")
    failed = bool(value.get("error")) or value.get("success") is False or value.get("ok") is False
    return rendered, int(failed)


def _perform(binary, options, env):
    sync(binary, env)
    if options.list:
        for name in list_items(binary, options.item, env):
            print(name.replace(env["BW_SESSION"], "***"))
        return 0
    secret = get_field(binary, options.field, options.item, env)
    if not secret:
        raise SecretError(f"Item has an empty {options.field}")
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from call_tool import load_context  # noqa: E402

    raw = load_context().dispatch_tool("android_type", {"text": secret, "clear_first": not options.no_clear})
    rendered, status = _tool_result(raw, secret, env["BW_SESSION"])
    print(rendered)
    return status


def _failure_info(error):
    safe = RuntimeError(f"{type(error).__name__} during type-secret")
    return RuntimeError, safe, error.__traceback__


def _report_failure(error):
    message = str(error) if type(error) is SecretError else f"Type secret failed: {type(error).__name__}"
    print(message, file=sys.stderr)


def _phone_online():
    try:
        from call_tool import load_context

        raw = load_context().dispatch_tool("android_ping", {})
        value = json.loads(raw) if isinstance(raw, str) else raw
        if (not isinstance(value, dict) or value.get("error") or value.get("success") is False
                or value.get("ok") is False or not any(value.get(key) is True for key in ("pong", "success", "ok"))):
            raise SecretError("android_ping failed")
        return True
    except (OSError, ValueError, TypeError, RuntimeError, ImportError) as error:
        logger.exception("component=type_secret operation=check_phone failed", exc_info=_failure_info(error))
        return False


def _check(binary, env):
    path = shutil.which(binary)
    if path is None:
        raise SecretError("Bitwarden CLI not found; install bw or set BW")
    print(f"Bitwarden CLI: {path}, version {version(path, env)}")
    server, state = status(path, env)
    print(f"Server: {server or 'bitwarden.com (default)'}")
    print(f"Vault: {state}")
    configured = bool(env["BW_SESSION"]) if "BW_SESSION" in env else bool(env.get("ATOMIC_BW_SESSION_CMD", "").strip())
    missing = "Run: bw login" if state == "unauthenticated" else NO_SESSION if state == "locked" and not configured else None
    online = _phone_online()
    print("Phone: online" if online else "Phone: offline (android_ping failed)")
    if missing or not online:
        raise SecretError(missing or "Phone check failed")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("item", nargs="?")
    parser.add_argument("--field", choices=["password", "username", "totp"], default="password")
    parser.add_argument("--no-clear", action="store_true")
    parser.add_argument("--list", action="store_true", help="list matching item names without login fields")
    parser.add_argument("--check", action="store_true", help="check CLI, vault and phone without acquiring a session")
    options = parser.parse_args(argv)
    if not options.check and options.item is None:
        parser.error("the following arguments are required: item")
    owned, env, status = False, dict(os.environ), 1
    try:
        binary = env.get("BW", "bw")
        if not binary:
            raise SecretError("BW must name an executable")
        if options.check:
            return _check(binary, env)
        value, owned = session(env)
        # Inherit the caller's appdata setting only when present; introduce no account defaults.
        env["BW_SESSION"] = value
        status = _perform(binary, options, env)
    except Exception as error:
        logger.exception("component=type_secret operation=invoke failed", exc_info=_failure_info(error))
        _report_failure(error)
    finally:
        if owned:
            try:
                lock(binary, env)
            except SecretError as error:
                logger.exception("component=type_secret operation=lock failed", exc_info=_failure_info(error))
                _report_failure(error)
                status = 1
    return status


if __name__ == "__main__":
    raise SystemExit(main())
