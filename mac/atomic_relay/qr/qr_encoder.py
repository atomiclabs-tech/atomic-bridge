"""Byte-mode QR Model 2 encoding with level M error correction."""

from itertools import groupby, product, zip_longest

from .qr_tables import (
    ALIGNMENT, BLOCKS, BYTE_CAPACITIES, DATA_CODEWORDS, EC_CODEWORDS,
    FORMAT_BITS, VERSION_BITS, QrMatrix,
)


def _data_codewords(data, version):
    capacity = DATA_CODEWORDS[version]
    bits = "0100" + format(len(data), "08b" if version < 10 else "016b")
    bits += "".join(f"{byte:08b}" for byte in data)
    bits += "0" * min(4, capacity * 8 - len(bits))
    bits += "0" * (-len(bits) % 8)
    result = bytearray(int(bits[i:i + 8], 2) for i in range(0, len(bits), 8))
    result.extend((0xEC, 0x11)[i % 2] for i in range(capacity - len(result)))
    return bytes(result)


def _multiply(left, right):
    result = 0
    while right:
        if right & 1:
            result ^= left
        left = (left << 1) ^ (0x11D if left & 0x80 else 0)
        right >>= 1
    return result


def _rs_remainder(data, degree):
    generator, root = [1], 1
    for _ in range(degree):
        expanded = [0] * (len(generator) + 1)
        for index, coefficient in enumerate(generator):
            expanded[index] ^= coefficient
            expanded[index + 1] ^= _multiply(coefficient, root)
        generator, root = expanded, _multiply(root, 2)
    remainder = [0] * degree
    for byte in data:
        factor = byte ^ remainder.pop(0)
        remainder.append(0)
        for index, coefficient in enumerate(generator[1:]):
            remainder[index] ^= _multiply(coefficient, factor)
    return bytes(remainder)


def _interleave(data, version):
    blocks, offset = [], 0
    for count, length in BLOCKS[version]:
        for _ in range(count):
            blocks.append(data[offset:offset + length])
            offset += length
    parity = [_rs_remainder(block, EC_CODEWORDS[version]) for block in blocks]
    return bytes(value for group in (blocks, parity) for column in zip_longest(*group)
                 for value in column if value is not None)


def _format(rows, mask):
    size, bits = len(rows), FORMAT_BITS[mask]
    first = [(8, y) for y in range(6)] + [(8, 7), (8, 8), (7, 8)]
    first += [(14 - bit, 8) for bit in range(9, 15)]
    second = [(size - 1 - bit, 8) for bit in range(8)]
    second += [(8, size - 15 + bit) for bit in range(8, 15)]
    for positions in (first, second):
        for bit, (x, y) in enumerate(positions):
            rows[y][x] = bool(bits & (1 << bit))
    rows[size - 8][8] = True


def _function_patterns(version):
    size = version * 4 + 17
    rows = [[-1] * size for _ in range(size)]
    for position in range(size):
        rows[6][position] = rows[position][6] = position % 2 == 0
    for cx, cy in [(3, 3), (size - 4, 3), (3, size - 4)]:
        for dy, dx in product(range(-4, 5), repeat=2):
            if 0 <= cy + dy < size and 0 <= cx + dx < size:
                rows[cy + dy][cx + dx] = max(abs(dx), abs(dy)) not in (2, 4)
    corners = {(6, 6), (6, size - 7), (size - 7, 6)}
    for cx, cy in product(ALIGNMENT[version], repeat=2):
        if (cx, cy) not in corners:
            for dy, dx in product(range(-2, 3), repeat=2):
                rows[cy + dy][cx + dx] = max(abs(dx), abs(dy)) != 1
    _format(rows, 0)
    if version >= 7:
        for bit in range(18):
            a, b = size - 11 + bit % 3, bit // 3
            rows[b][a] = rows[a][b] = bool(VERSION_BITS[version] & (1 << bit))
    return rows


def _place_data(pattern):
    size, upward, right, positions = len(pattern), True, len(pattern) - 1, []
    while right > 0:
        if right == 6:
            right -= 1
        scan = range(size - 1, -1, -1) if upward else range(size)
        positions.extend((x, y) for y in scan for x in (right, right - 1) if pattern[y][x] == -1)
        right -= 2
        upward = not upward
    return positions


def _mask(mask, x, y):
    return (
        (x + y) % 2 == 0, y % 2 == 0, x % 3 == 0, (x + y) % 3 == 0,
        (x // 3 + y // 2) % 2 == 0, x * y % 2 + x * y % 3 == 0,
        (x * y % 2 + x * y % 3) % 2 == 0, ((x + y) % 2 + x * y % 3) % 2 == 0,
    )[mask]


def _penalties(rows):
    run_score, square_score, finder_score = 0, 0, 0
    for line in [*rows, *zip(*rows)]:
        for _, run in groupby(line):
            length = sum(1 for _ in run)
            run_score += max(0, length - 2) if length >= 5 else 0
        text = "".join("1" if cell else "0" for cell in line)
        finder_score += 40 * sum(text[i:i + 11] in ("00001011101", "10111010000")
                                 for i in range(len(text) - 10))
    for y in range(len(rows) - 1):
        for x in range(len(rows[0]) - 1):
            if rows[y][x] == rows[y][x + 1] == rows[y + 1][x] == rows[y + 1][x + 1]:
                square_score += 3
    total = len(rows) * len(rows[0])
    dark = sum(sum(row) for row in rows)
    balance_score = abs(20 * dark - 10 * total) // total * 10
    return run_score, square_score, finder_score, balance_score


def encode(text: str) -> QrMatrix:
    """Encode UTF-8 bytes at level M, selecting the smallest version up to 10."""
    data = text.encode("utf-8")
    version = next((v for v in range(1, 11) if len(data) <= BYTE_CAPACITIES[v]), None)
    if version is None:
        raise ValueError("QR version-10-M byte capacity exceeded")
    bits = "".join(f"{byte:08b}" for byte in _interleave(_data_codewords(data, version), version))
    pattern = _function_patterns(version)
    candidates = []
    for mask in range(8):
        rows = [row.copy() for row in pattern]
        for index, (x, y) in enumerate(_place_data(pattern)):
            rows[y][x] = (index < len(bits) and bits[index] == "1") ^ _mask(mask, x, y)
        _format(rows, mask)
        candidates.append(rows)
    best = min(candidates, key=lambda rows: sum(_penalties(rows)))
    return QrMatrix(len(best), tuple(tuple(row) for row in best))
