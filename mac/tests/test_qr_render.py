import struct
import zlib

import pytest

from atomic_relay.qr import encode, render_terminal, write_png


def png_pixels(path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    chunks, cursor = [], 8
    while cursor < len(data):
        length = struct.unpack(">I", data[cursor:cursor + 4])[0]
        kind = data[cursor + 4:cursor + 8]
        body = data[cursor + 8:cursor + 8 + length]
        checksum = struct.unpack(">I", data[cursor + 8 + length:cursor + 12 + length])[0]
        assert checksum == zlib.crc32(kind + body)
        chunks.append((kind, body))
        cursor += 12 + length
    assert cursor == len(data)
    assert [kind for kind, _ in chunks] == [b"IHDR", b"IDAT", b"IEND"]
    width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", chunks[0][1])
    assert (depth, color, compression, filtering, interlace) == (1, 0, 0, 0, 0)
    assert chunks[-1][1] == b""
    raw = zlib.decompress(b"".join(body for kind, body in chunks if kind == b"IDAT"))
    stride = (width + 7) // 8
    assert len(raw) == height * (stride + 1)
    pixels = []
    for y in range(height):
        offset = y * (stride + 1)
        assert raw[offset] == 0
        row = raw[offset + 1:offset + stride + 1]
        pixels.append([not bool(row[x // 8] & (1 << (7 - x % 8))) for x in range(width)])
    return width, height, pixels


@pytest.mark.parametrize("scale", [1, 3, 8])
@pytest.mark.parametrize("text", ["", "HELLO WORLD", "💡" * 30])
def test_png_raster_and_four_module_quiet_zone(tmp_path, text, scale):
    matrix = encode(text)
    path = tmp_path / "qr.png"
    assert write_png(matrix, path, scale=scale) is None
    width, height, pixels = png_pixels(path)
    assert width == height == (matrix.size + 8) * scale
    for y, row in enumerate(pixels):
        for x, dark in enumerate(row):
            mx, my = x // scale - 4, y // scale - 4
            expected = 0 <= mx < matrix.size and 0 <= my < matrix.size and matrix.rows[my][mx]
            assert dark == expected


@pytest.mark.parametrize("scale", [0, -1, 1.5, True])
def test_png_invalid_scale_is_loud(tmp_path, scale):
    path = tmp_path / "qr.png"
    with pytest.raises(ValueError, match="positive integer"):
        write_png(encode("test"), path, scale=scale)
    assert not path.exists()


def test_png_write_failure_propagates(tmp_path):
    with pytest.raises(OSError):
        write_png(encode("test"), tmp_path / "missing" / "qr.png")


@pytest.mark.parametrize("length", [0, 14, 62, 213])
def test_terminal_roundtrip_width_quiet_zone_and_no_trailing_spaces(length):
    matrix = encode("x" * length)
    lines = render_terminal(matrix).splitlines()
    width = matrix.size + 8
    assert len(lines) == (width + 1) // 2
    assert all(len(line) == width and line == line.rstrip() for line in lines)
    # A light foreground draws the white modules; spaces draw the dark modules.
    glyphs = {"█": (False, False), "▀": (False, True),
              "▄": (True, False), " ": (True, True)}
    pixels = []
    for line in lines:
        pixels.extend([[glyphs[character][half] for character in line] for half in range(2)])
    for y, row in enumerate(pixels):
        for x, dark in enumerate(row):
            mx, my = x - 4, y - 4
            expected = 0 <= mx < matrix.size and 0 <= my < matrix.size and matrix.rows[my][mx]
            assert dark == expected
