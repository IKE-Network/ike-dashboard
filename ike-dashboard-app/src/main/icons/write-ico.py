"""Writes a Windows .ico holding the given PNGs, each stored as PNG (Windows Vista and later).

Usage: python3 write-ico.py OUT.ico IN-16.png IN-32.png ...
"""
import struct
import sys


def size(png):
    width, height = struct.unpack(">II", png[16:24])
    return width, height


def main(out, inputs):
    images = [open(path, "rb").read() for path in inputs]
    offset = 6 + 16 * len(images)
    header = struct.pack("<HHH", 0, 1, len(images))
    entries = b""
    for png in images:
        width, height = size(png)
        # 256 is written as 0, as the format has one byte for each dimension.
        entries += struct.pack("<BBBBHHII", width % 256, height % 256, 0, 0, 1, 32, len(png), offset)
        offset += len(png)
    with open(out, "wb") as ico:
        ico.write(header + entries + b"".join(images))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
