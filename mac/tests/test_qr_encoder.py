import itertools
import secrets
from urllib.parse import urlencode

import pytest

from atomic_relay.qr import QrMatrix, encode
from atomic_relay.qr.qr_encoder import (
    _data_codewords, _format, _function_patterns, _interleave, _mask, _penalties,
    _place_data, _rs_remainder,
)
from atomic_relay.qr.qr_tables import BLOCKS, BYTE_CAPACITIES, EC_CODEWORDS


def test_byte_mode_tutorial_vector():
    assert _data_codewords(b"HELLO WORLD", 1) == bytes([
        64, 180, 132, 84, 196, 196, 242, 5, 116, 245, 36, 196, 64, 236, 17, 236,
    ])
    assert _data_codewords(b"", 1) == bytes([64, 0] + [236, 17] * 7)
    assert _data_codewords(b"a", 1) == bytes([64, 22, 16] + [236, 17] * 6 + [236])


def polynomial_parity(data, degree):
    # Independent log/antilog multiplication and full polynomial long division.
    powers, value = [], 1
    for _ in range(255):
        powers.append(value)
        value <<= 1
        if value & 256:
            value ^= 0x11D
    logs = {value: index for index, value in enumerate(powers)}

    def multiply(left, right):
        return powers[(logs[left] + logs[right]) % 255] if left and right else 0

    divisor = [1]
    for root in powers[:degree]:
        expanded = [0] * (len(divisor) + 1)
        for index, coefficient in enumerate(divisor):
            expanded[index] ^= coefficient
            expanded[index + 1] ^= multiply(coefficient, root)
        divisor = expanded
    dividend = list(data) + [0] * degree
    for index in range(len(data)):
        factor = dividend[index]
        for offset, coefficient in enumerate(divisor):
            dividend[index + offset] ^= multiply(coefficient, factor)
    return bytes(dividend[-degree:])


@pytest.mark.parametrize("degree", [10, 16, 18, 22, 24, 26])
def test_reed_solomon_independent_division(degree):
    block = bytes([32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17])
    assert _rs_remainder(block, degree) == polynomial_parity(block, degree)
    if degree == 10:
        assert _rs_remainder(block, degree) == bytes([196, 35, 39, 119, 235, 215, 231, 226, 93, 23])


@pytest.mark.parametrize("version", [1, 5])
def test_function_pattern_coordinates(version):
    rows = _function_patterns(version)
    size = version * 4 + 17
    for cx, cy in [(3, 3), (size - 4, 3), (3, size - 4)]:
        for dy, dx in itertools.product(range(-4, 5), repeat=2):
            if 0 <= cy + dy < size and 0 <= cx + dx < size:
                assert rows[cy + dy][cx + dx] == (max(abs(dx), abs(dy)) not in (2, 4))
    for position in range(8, size - 8):
        assert rows[6][position] == (position % 2 == 0)
        assert rows[position][6] == (position % 2 == 0)
    if version == 5:
        for dy, dx in itertools.product(range(-2, 3), repeat=2):
            assert rows[30 + dy][30 + dx] == (max(abs(dx), abs(dy)) != 1)


def format_mask(rows):
    positions = [(8, y) for y in range(6)] + [(8, 7), (8, 8), (7, 8)]
    positions += [(14 - bit, 8) for bit in range(9, 15)]
    encoded = sum(int(rows[y][x]) << bit for bit, (x, y) in enumerate(positions))
    unmasked = encoded ^ 0x5412
    remainder = unmasked
    for shift in range(14, 9, -1):
        if remainder & (1 << shift):
            remainder ^= 0x537 << (shift - 10)
    assert remainder == 0
    assert unmasked >> 13 == 0  # Level M has the two-bit indicator 00.
    mask = (unmasked >> 10) & 7
    size = len(rows)
    other = [(size - 1 - bit, 8) for bit in range(8)]
    other += [(8, size - 15 + bit) for bit in range(8, 15)]
    assert sum(int(rows[y][x]) << bit for bit, (x, y) in enumerate(other)) == encoded
    return mask


@pytest.mark.parametrize("version", range(1, 11))
def test_version_capacity_interleave_placement_and_byte_roundtrip(version):
    payload = ("x" * BYTE_CAPACITIES[version]).encode()
    matrix = encode(payload.decode())
    assert isinstance(matrix, QrMatrix)
    assert matrix.size == version * 4 + 17
    assert all(type(module) is bool for row in matrix.rows for module in row)
    mask = format_mask(matrix.rows)
    pattern = _function_patterns(version)
    columns = [right if right > 6 else right - 1 for right in range(matrix.size - 1, 0, -2)]
    positions = [(x, y) for stripe, right in enumerate(columns)
                 for y in (range(matrix.size - 1, -1, -1) if stripe % 2 == 0 else range(matrix.size))
                 for x in (right, right - 1) if pattern[y][x] == -1]
    bits = [int(matrix.rows[y][x]) ^ _mask(mask, x, y) for x, y in positions]
    codewords = bytes(sum(bits[offset + bit] << (7 - bit) for bit in range(8))
                      for offset in range(0, len(bits) - 7, 8))
    data = _data_codewords(payload, version)
    assert codewords == _interleave(data, version)
    lengths = [length for count, length in BLOCKS[version] for _ in range(count)]
    blocks = [bytearray() for _ in lengths]
    cursor = 0
    for column in range(max(lengths)):
        for index, length in enumerate(lengths):
            if column < length:
                blocks[index].append(codewords[cursor])
                cursor += 1
    ecc = EC_CODEWORDS[version]
    for index, block in enumerate(blocks):
        assert bytes(codewords[cursor + index::len(blocks)]) == polynomial_parity(block, ecc)
    recovered = "".join(f"{byte:08b}" for block in blocks for byte in block)
    count_bits = 8 if version < 10 else 16
    assert recovered[:4] == "0100"
    count = int(recovered[4:4 + count_bits], 2)
    assert count == len(payload)
    start = 4 + count_bits
    assert bytes(int(recovered[i:i + 8], 2) for i in range(start, start + count * 8, 8)) == payload
    assert all(bit == 0 for bit in bits[len(codewords) * 8:])


def test_utf8_and_capacity_failure():
    assert encode("é" * 7).size == 21
    assert encode("é" * 8).size == 25
    with pytest.raises(ValueError, match="capacity"):
        encode("x" * 214)
    with pytest.raises(ValueError, match="capacity"):
        encode("💡" * 54)


@pytest.mark.parametrize("length, expected", [(5, 3), (6, 4), (7, 5)])
def test_run_penalty(length, expected):
    assert _penalties([[True] * length])[0] == expected
    assert _penalties([[False] for _ in range(length)])[0] == expected


def test_square_finder_and_balance_penalties():
    assert _penalties([[True, True], [True, True]])[1] == 3
    assert _penalties([[True, False], [False, True]])[1] == 0
    for line in ["10111010000", "00001011101"]:
        row = [character == "1" for character in line]
        assert _penalties([row])[2] == 40
        assert _penalties([[cell] for cell in row])[2] == 40
    assert _penalties([[True, False] * 5])[3] == 0
    assert _penalties([[True] * 6 + [False] * 4])[3] == 20


def test_encoder_selects_minimum_penalty_mask():
    matrix = encode("HELLO WORLD")
    chosen = format_mask(matrix.rows)
    base = _function_patterns(1)
    bits = "".join(f"{byte:08b}" for byte in _interleave(_data_codewords(b"HELLO WORLD", 1), 1))
    scores = []
    for mask in range(8):
        candidate = [row.copy() for row in base]
        for index, (x, y) in enumerate(_place_data(base)):
            candidate[y][x] = (index < len(bits) and bits[index] == "1") ^ _mask(mask, x, y)
        _format(candidate, mask)
        scores.append(sum(_penalties(candidate)))
    assert scores[chosen] == min(scores)


@pytest.mark.parametrize("version", range(7, 11))
def test_version_information_bch_and_both_copies(version):
    rows = encode("x" * (BYTE_CAPACITIES[version - 1] + 1)).rows
    size = len(rows)
    info = sum(int(rows[bit // 3][size - 11 + bit % 3]) << bit for bit in range(18))
    assert sum(int(rows[size - 11 + bit % 3][bit // 3]) << bit for bit in range(18)) == info
    assert info >> 12 == version
    for shift in range(17, 11, -1):
        if info & (1 << shift):
            info ^= 0x1F25 << (shift - 12)
    assert info == 0


def test_pairing_payload_byte_length_roundtrip():
    text = "atomic://pair?" + urlencode({"relay": "wss://example.test", "code": secrets.token_hex(3)})
    data = _data_codewords(text.encode(), 5)
    bits = "".join(f"{byte:08b}" for byte in data)
    count = int(bits[4:12], 2)
    assert count == len(text.encode())
    recovered = bytes(int(bits[i:i + 8], 2) for i in range(12, 12 + count * 8, 8))
    assert bool(recovered == text.encode())
