"""Quiet-zone rendering without image or terminal dependencies."""

import struct
import zlib
from pathlib import Path

from .qr_tables import QrMatrix


def _padded(matrix):
    quiet = (False,) * (matrix.size + 8)
    return (quiet,) * 4 + tuple((False,) * 4 + row + (False,) * 4 for row in matrix.rows) + (quiet,) * 4


def render_terminal(matrix: QrMatrix) -> str:
    """Draw white modules with a light foreground on a dark terminal background."""
    rows = _padded(matrix)
    rows += ((False,) * len(rows),)
    return "\n".join("".join(("█", "▀", "▄", " ")[2 * top + bottom]
                             for top, bottom in zip(rows[y], rows[y + 1]))
                     for y in range(0, len(rows) - 1, 2))


def _chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def write_png(matrix: QrMatrix, path: Path, *, scale: int = 8) -> None:
    """Write a 1-bit grayscale PNG with a four-module white quiet zone."""
    if not isinstance(scale, int) or isinstance(scale, bool) or scale < 1:
        raise ValueError("QR scale must be a positive integer")
    width = (matrix.size + 8) * scale
    raw = bytearray()
    for row in _padded(matrix):
        bits = "".join(("0" if dark else "1") * scale for dark in row)
        bits += "1" * (-len(bits) % 8)
        scanline = b"\0" + int(bits, 2).to_bytes(len(bits) // 8, "big")
        raw.extend(scanline * scale)
    header = struct.pack(">IIBBBBB", width, width, 1, 0, 0, 0, 0)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", header)
                     + _chunk(b"IDAT", zlib.compress(raw)) + _chunk(b"IEND", b""))
