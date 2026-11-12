"""Standard-library QR encoding and rendering for pairing output."""

from .qr_encoder import encode
from .qr_render import render_terminal, write_png
from .qr_tables import QrMatrix

__all__ = ["QrMatrix", "encode", "render_terminal", "write_png"]
