"""A PNG decoder, because this repository installs no Python packages.

Shared by `snapshot-index.py`, which measures how flat a frame is, and by
`tests/test_ios_preview_baseline.py`, which compares one against its
reference. Both need pixels and neither is worth a dependency.

8-bit, non-interlaced only — which is what `UIGraphicsImageRenderer` and
Compose both emit. Anything else raises rather than guessing.
"""
import struct
import zlib


def decode(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat, ihdr = 8, [], None
    while pos < len(data):
        length, typ = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if typ == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", body)
        elif typ == b"IDAT":
            idat.append(body)
        elif typ == b"IEND":
            break
        pos += 12 + length
    w, h, depth, colour, _, _, interlace = ihdr
    assert depth == 8 and interlace == 0, f"unsupported PNG: depth={depth}"
    channels = {0: 1, 2: 3, 4: 2, 6: 4}[colour]
    raw = zlib.decompress(b"".join(idat))
    stride = w * channels
    out, prev = [], bytearray(stride)
    i = 0
    for _ in range(h):
        f = raw[i]; i += 1
        line = bytearray(raw[i:i + stride]); i += stride
        if f == 1:
            for x in range(channels, stride):
                line[x] = (line[x] + line[x - channels]) & 0xFF
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 0xFF
        elif f == 3:
            for x in range(stride):
                a = line[x - channels] if x >= channels else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 0xFF
        elif f == 4:
            for x in range(stride):
                a = line[x - channels] if x >= channels else 0
                b = prev[x]
                c = prev[x - channels] if x >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 0xFF
        out.append(bytes(line)); prev = line
    return w, h, channels, out
