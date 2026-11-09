"""Draws the app icon (the phone's: italic blue H with an amber sound wave, on near-black) and
writes assets/icon.ico for the desktop .exe. Only numpy and the standard library (no PIL).

    python tools\\make_icon.py

Shapes are the phone icon's (res/drawable/ic_launcher_fg.xml), in its 160-unit space."""
import os
import struct
import zlib

import numpy as np

NIGHT = (0x08, 0x09, 0x0C)
BLUE = (0x19, 0x76, 0xD2)
AMBER = (0xFF, 0xA0, 0x00)
H_SIDES = [[(30, 132), (54, 132), (80, 28), (56, 28)], [(86, 132), (110, 132), (136, 28), (112, 28)]]


def wave_points():
    """M34,80 Q46,48 58,80 T82,80 T106,80 T130,80 as points (quadratic curves, reflected controls)."""
    pts, start, ctrl = [], (34, 80), (46, 48)
    for end in [(58, 80), (82, 80), (106, 80), (130, 80)]:
        for t in np.linspace(0, 1, 60):
            pts.append(((1 - t) ** 2 * start[0] + 2 * (1 - t) * t * ctrl[0] + t * t * end[0],
                        (1 - t) ** 2 * start[1] + 2 * (1 - t) * t * ctrl[1] + t * t * end[1]))
        ctrl = (2 * end[0] - ctrl[0], 2 * end[1] - ctrl[1])
        start = end
    return np.array(pts)


def render(size):
    ss = 4                                            # supersampling for smooth edges
    n = size * ss
    scale = n / 160.0
    ys, xs = np.mgrid[0:n, 0:n] + 0.5
    ux, uy = xs / scale, ys / scale                   # pixel centres in icon units
    img = np.zeros((n, n, 3))
    img[:] = NIGHT
    for poly in H_SIDES:                              # the H's two slanted sides
        inside = np.ones((n, n), bool)
        for i in range(4):
            (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % 4]
            inside &= (x2 - x1) * (uy - y1) - (y2 - y1) * (ux - x1) <= 0
        img[inside] = BLUE
    pts = wave_points()
    d = np.full((n, n), 1e9)
    for px, py in pts[::2]:
        d = np.minimum(d, (ux - px) ** 2 + (uy - py) ** 2)
    d = np.sqrt(d)
    img[d < 10] = NIGHT                               # dark outline keeps the wave apart from the H
    img[d < 5.5] = AMBER
    img = img.reshape(size, ss, size, ss, 3).mean(axis=(1, 3))
    return img.round().astype(np.uint8)


def png(rgb):
    h, w, _ = rgb.shape
    raw = b"".join(b"\x00" + rgb[y].tobytes() for y in range(h))

    def chunk(t, data):
        return struct.pack(">I", len(data)) + t + data + struct.pack(">I", zlib.crc32(t + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def ico(sizes):
    images = [png(render(s)) for s in sizes]
    out = struct.pack("<HHH", 0, 1, len(images))
    offset = 6 + 16 * len(images)
    for s, data in zip(sizes, images):
        out += struct.pack("<BBBBHHII", s % 256, s % 256, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    return out + b"".join(images)


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "..", "assets", "icon.ico")
    open(path, "wb").write(ico([256, 64, 48, 32, 16]))
    open(os.path.join(here, "..", "assets", "icon_256.png"), "wb").write(png(render(256)))
    print("wrote", os.path.normpath(path))
