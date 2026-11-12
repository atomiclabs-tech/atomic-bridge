# no_parallel: isolated aiohttp servers bind port 0 and CLI tests run subprocesses.
import asyncio
import base64
import json
import runpy
import subprocess
import sys
from pathlib import Path

import pytest

from e2e_support import loopback, upload
from session_cases import admin_headers
from tool_cases import PNG

SCRIPT = Path(__file__).resolve().parents[2] / "tools" / "call_tool.py"
HELPER = runpy.run_path(str(SCRIPT))


async def call_script(name, args, tmp_path):
    return await asyncio.wait_for(asyncio.to_thread(
        subprocess.run, [sys.executable, str(SCRIPT), name, json.dumps(args), "--image-dir", str(tmp_path)],
        check=True, timeout=5, capture_output=True, text=True, cwd=tmp_path,
    ), timeout=6)


def test_cli_ping_uses_real_registered_handler_and_exits_one_when_server_down(tmp_path, monkeypatch):
    async def check():
        async with loopback(tmp_path, monkeypatch) as relay:
            phone = await relay.connect()
            await phone.status()
            process = asyncio.create_task(call_script("android_ping", {}, tmp_path))
            command = await phone.receive()
            assert command["payload"]["path"] == "/ping"
            await phone.respond(command, result={"success": True, "pong": True})
            completed = await process
            assert completed.returncode == 0
            assert json.loads(completed.stdout) == {"success": True, "pong": True}
            assert completed.stderr == ""
            await phone.close()
        with pytest.raises(subprocess.CalledProcessError) as stopped:
            await call_script("android_ping", {}, tmp_path)
        assert stopped.value.returncode == 1
        assert json.loads(stopped.value.stdout) == {"error": "Relay GET failed: URLError"}
        assert "component=client operation=GET failed" in stopped.value.stderr
        leaked = any(private in stopped.value.stdout + stopped.value.stderr for private in (
            relay.session.token, admin_headers(relay.app)["Authorization"][7:],
        ))
        assert leaked is False
    asyncio.run(check())


def test_cli_screenshot_saves_uploaded_png_without_base64_or_media_token(tmp_path, monkeypatch):
    async def check():
        async with loopback(tmp_path, monkeypatch) as relay:
            phone = await relay.connect()
            await phone.status()
            process = asyncio.create_task(call_script("android_screenshot", {}, tmp_path))
            command = await phone.receive()
            assert command["payload"]["path"] == "/screenshot"
            image = await upload(relay, [PNG], content_type="image/png")
            await phone.respond(command, result={"success": True, "media": "MEDIA:hermes-relay://" + image["token"]})
            completed = await process
            saved = json.loads(completed.stdout)["images"]
            assert len(saved) == 1 and saved[0]["content_type"] == "image/png"
            path = Path(saved[0]["path"])
            same_image = path.read_bytes() == PNG
            assert same_image and path.parent == tmp_path
            leaked = any(private in completed.stdout + completed.stderr for private in (
                image["token"], relay.session.token, admin_headers(relay.app)["Authorization"][7:],
                base64.b64encode(PNG).decode(), "MEDIA:hermes-relay://",
            ))
            assert leaked is False
            await phone.close()
    asyncio.run(check())


@pytest.mark.parametrize("arguments,reason", [("{", "valid JSON"), ("[]", "JSON object")])
def test_invalid_cli_arguments_are_structured_failures(arguments, reason, capsys, monkeypatch):
    monkeypatch.setitem(HELPER["main"].__globals__, "load_context", lambda: pytest.fail("Invalid arguments reached plugin"))
    assert HELPER["main"](["android_ping", arguments]) == 1
    captured = capsys.readouterr()
    assert reason in json.loads(captured.out)["error"]


def test_unknown_tool_returns_clear_error_after_loading_real_local_plugin(capsys):
    assert HELPER["main"](["android_missing", "{}"] ) == 1
    assert json.loads(capsys.readouterr().out) == {"error": "Unknown Atomic tool name"}


def test_malformed_image_rolls_back_already_written_images(tmp_path):
    value = {"content": [
        {"type": "image_url", "image_url": {"url": "data:image/png;base64," + base64.b64encode(PNG).decode()}},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,invalid!"}},
    ]}
    with pytest.raises(ValueError, match="invalid base64"):
        HELPER["save_images"](value, tmp_path)
    assert not list(tmp_path.iterdir())


def test_output_failure_status_and_media_marker_redaction():
    rendered, status = HELPER["format_result"](json.dumps({"success": False, "code": "internal_error", "error": "Synthetic failure"}), None)
    assert status == 1 and json.loads(rendered)["code"] == "internal_error"
    rendered, status = HELPER["format_result"]({"media": "MEDIA:hermes-relay://synthetic_private_media"}, None)
    assert status == 0 and json.loads(rendered) == {"media": "[private media]"}
