import argparse
import math
from pathlib import Path
import random
import struct
import zlib


EDGE = 128
MATERIALS = ("graphite", "charcoal", "bristle", "dry-bristle", "pigment", "canvas", "wash")


def clamp(value):
    return max(0.0, min(1.0, value))


def field(seed, cells):
    rng = random.Random(seed)
    points = [[rng.random() for _ in range(cells)] for _ in range(cells)]

    def sample(x, y):
        sx, sy = x * cells / EDGE, y * cells / EDGE
        ix, iy = math.floor(sx), math.floor(sy)
        fx, fy = sx - ix, sy - iy
        fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        top = points[iy % cells][ix % cells] * (1 - fx) + points[iy % cells][(ix + 1) % cells] * fx
        bottom = points[(iy + 1) % cells][ix % cells] * (1 - fx) + points[(iy + 1) % cells][(ix + 1) % cells] * fx
        return top * (1 - fy) + bottom * fy

    return sample


def material(name, index):
    rng = random.Random(32081 + index * 117)
    coarse = field(5903 + index, 8)
    medium = field(2309 + index, 32)
    fine = field(8909 + index, 64)
    strokes = [(rng.uniform(-0.95, 0.95), rng.uniform(0.008, 0.055), rng.uniform(0.4, 1.0)) for _ in range(36)]
    tip, paper = bytearray(), bytearray()
    for y in range(EDGE):
        for x in range(EDGE):
            u, v = (x + 0.5) / EDGE * 2 - 1, (y + 0.5) / EDGE * 2 - 1
            c, m, f, dust = coarse(x, y), medium(x, y), fine(x, y), rng.random()
            if name == "graphite":
                t = 0.65 + 0.35 * m
                p = clamp((0.30 * m + 0.45 * f + 0.25 * dust - 0.43) * 6.0)
            elif name == "charcoal":
                t = clamp(0.22 + c * 0.9 + m * 0.25)
                p = clamp((0.15 * m + 0.20 * f + 0.65 * dust - 0.27) * 3.8)
            elif name in ("bristle", "dry-bristle"):
                hair = 0.0
                for position, width, load in strokes:
                    center = position + 0.017 * math.sin(u * 5.7 + position * 19)
                    hair = max(hair, load * clamp(1 - abs(v - center) / width))
                t = clamp((0.26 if name == "bristle" else 0.0) + hair * (0.75 + 0.25 * m))
                if name == "dry-bristle":
                    t *= clamp((c + 0.25 * m - 0.20) * 2.4)
                    p = clamp((0.10 * m + 0.25 * f + 0.65 * dust - 0.28) * 4.0)
                else:
                    p = 0.7 + 0.3 * m
            elif name == "pigment":
                t = clamp(0.35 + c * 0.50 + m * 0.3)
                p = clamp((0.50 * m + 0.30 * f + 0.20 * dust - 0.32) * 3.7)
            elif name == "canvas":
                t = 0.7 + 0.3 * c
                warp = abs(math.sin((x + 4 * m + 2 * c + 1.2 * math.sin(y * 0.17)) * math.pi / 4.5))
                weft = abs(math.sin((y + 4 * f + 2 * c + 1.2 * math.sin(x * 0.13)) * math.pi / 4.1))
                p = clamp((warp * weft + dust * 0.14 - 0.13) * 2.3) * (0.65 + 0.35 * f)
            else:
                radius = math.hypot(u, v)
                rim = math.exp(-((radius - 0.76 - 0.10 * (c - 0.5)) / 0.11) ** 2)
                t = clamp(0.18 + c * 0.17 + m * 0.15 + rim * 0.55)
                p = clamp((0.15 * c + 0.35 * m + 0.50 * f - 0.15) * 2.2)
            tip.append(round(clamp(t) * 255))
            paper.append(round(clamp(p) * 255))
    return tip, paper


def png(path, width, height, pixels):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    raw = b"".join(b"\0" + pixels[y * width * 3:(y + 1) * width * 3] for y in range(height))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "engine/assets/brushes")
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    atlas = bytearray(EDGE * len(MATERIALS) * EDGE * 2 * 3)
    for index, name in enumerate(MATERIALS):
        tip, paper = material(name, index)
        (args.output / f"{name}-tip.bin").write_bytes(tip)
        (args.output / f"{name}-paper.bin").write_bytes(paper)
        for row, source in enumerate((tip, paper)):
            for y in range(EDGE):
                for x in range(EDGE):
                    offset = (((row * EDGE + y) * EDGE * len(MATERIALS)) + index * EDGE + x) * 3
                    atlas[offset:offset + 3] = bytes([source[y * EDGE + x]]) * 3
    if args.preview:
        args.preview.parent.mkdir(parents=True, exist_ok=True)
        png(args.preview, EDGE * len(MATERIALS), EDGE * 2, atlas)


if __name__ == "__main__":
    main()
