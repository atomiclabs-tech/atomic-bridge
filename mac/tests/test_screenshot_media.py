import base64

import pytest

from atomic_bridge import screenshot_media
from atomic_bridge.client import RelayClient
from atomic_bridge.screenshot_media import ScreenshotMediaError, resolve_screenshot
from fake_relay import admin_environment
from tool_cases import MARKER, PNG, MediaOpener

JPEG = b"\xff\xd8\xff\xc0\x00\x0b\x08\x00\x02\x00\x03\x01\x01\x11\x00\xff\xd9"


def _client(opener):
    return RelayClient(environ={"ANDROID_BRIDGE_URL": "http://127.0.0.1", **admin_environment()}, opener=opener)


@pytest.mark.parametrize("raw", [{"image": base64.b64encode(PNG).decode()}, {"data": {"image": base64.b64encode(PNG).decode()}}])
def test_legacy_inline_png_is_bounded_and_decoded(raw):
    result = resolve_screenshot(raw, None)
    assert result.data == PNG and result.content_type == "image/png"
    assert (result.width, result.height, result.marker) == (1, 1, None)


def test_jpeg_dimensions_and_type_match():
    image = resolve_screenshot({"media": MARKER}, _client(MediaOpener(image=JPEG, mime="image/jpeg")))
    assert (image.content_type, image.width, image.height) == ("image/jpeg", 3, 2)


@pytest.mark.parametrize("raw,cause", [
    ([], "Invalid screenshot response"), ({"data": []}, "Invalid screenshot response"),
    ({}, "No image data"), ({"image": "invalid!"}, "Invalid screenshot image data"),
    ({"media": "MEDIA:hermes-relay://../invalid"}, "Invalid screenshot media token"),
    ({"image": base64.b64encode(b"not an image").decode()}, "PNG or JPEG"),
    ({"image": base64.b64encode(b"\xff\xd8\xff").decode()}, "JPEG"),
    ({"image": base64.b64encode(PNG[:16] + bytes(8)).decode()}, "dimensions"),
])
def test_invalid_image_contract_names_real_decode_cause(raw, cause):
    with pytest.raises(ScreenshotMediaError) as error:
        resolve_screenshot(raw, None)
    assert cause in error.value.value["error"]
    assert error.value.value["stage"] == "decode"


def test_media_magic_must_match_declared_type():
    with pytest.raises(ScreenshotMediaError) as error:
        resolve_screenshot({"media": MARKER}, _client(MediaOpener(image=JPEG, mime="image/png")))
    assert "type mismatch" in error.value.value["error"]


def test_inline_size_bound_checked_before_decode(monkeypatch):
    monkeypatch.setattr(screenshot_media, "MAX_SCREENSHOT_BYTES", 2)
    with pytest.raises(ScreenshotMediaError) as error:
        resolve_screenshot({"image": base64.b64encode(PNG).decode()}, None)
    assert "2 bytes" in error.value.value["error"]


def test_failed_download_discards_partial_buffer():
    class Partial:
        def download(self, path, sink, *, max_bytes):
            sink.write(b"partial")
            self.sink = sink
            return type("Response", (), {"status": None, "value": {"error": "synthetic interrupted fetch"}})()

    client = Partial()
    with pytest.raises(ScreenshotMediaError) as error:
        resolve_screenshot({"media": MARKER}, client)
    assert error.value.value == {"error": "synthetic interrupted fetch", "stage": "fetch"}
    assert client.sink.closed
