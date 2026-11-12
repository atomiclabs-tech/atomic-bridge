# forge-allow: no-test declarations only
"""QR Model 2, error-correction level M, versions 1 through 10."""

from dataclasses import dataclass


@dataclass(frozen=True)
class QrMatrix:
    size: int
    rows: tuple[tuple[bool, ...], ...]


# Version-indexed entries; each block group is (count, data codewords per block).
BLOCKS = (
    (), ((1, 16),), ((1, 28),), ((1, 44),), ((2, 32),), ((2, 43),),
    ((4, 27),), ((4, 31),), ((2, 38), (2, 39)),
    ((3, 36), (2, 37)), ((4, 43), (1, 44)),
)
DATA_CODEWORDS = (0, 16, 28, 44, 64, 86, 108, 124, 154, 182, 216)
EC_CODEWORDS = (0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
BYTE_CAPACITIES = (0, 14, 26, 42, 62, 84, 106, 122, 152, 180, 213)
ALIGNMENT = (
    (), (), (6, 18), (6, 22), (6, 26), (6, 30), (6, 34),
    (6, 22, 38), (6, 24, 42), (6, 26, 46), (6, 28, 50),
)
FORMAT_BITS = (0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0)
VERSION_BITS = {7: 0x07C94, 8: 0x085BC, 9: 0x09A99, 10: 0x0A4D3}
