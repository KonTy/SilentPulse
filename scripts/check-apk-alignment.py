#!/usr/bin/env python3
"""Reject APKs with 64-bit native libraries that cannot load on 16 KB pages."""

import argparse
from pathlib import Path
import struct
import sys
import zipfile


PAGE_SIZE = 16 * 1024
ABIS = {"arm64-v8a", "x86_64"}


def elf_errors(data):
    if len(data) < 64 or data[:5] != b"\x7fELF\x02" or data[5] not in (1, 2):
        raise ValueError("expected a valid 64-bit ELF header")

    endian = "<" if data[5] == 1 else ">"
    table_offset = struct.unpack_from(endian + "Q", data, 32)[0]
    entry_size, entry_count = struct.unpack_from(endian + "HH", data, 54)
    entry = struct.Struct(endian + "IIQQQQQQ")
    if (entry_size < entry.size or entry_count == 0 or
            table_offset + entry_size * entry_count > len(data)):
        raise ValueError("invalid ELF program header table")

    errors = []
    load_count = 0
    for index in range(entry_count):
        kind, _, offset, address, _, _, _, alignment = entry.unpack_from(
            data, table_offset + index * entry_size
        )
        if kind != 1:  # PT_LOAD
            continue
        load_count += 1
        if alignment < PAGE_SIZE or alignment & (alignment - 1):
            errors.append(f"LOAD segment {index} alignment is {alignment}, need >= {PAGE_SIZE}")
        if (address - offset) % PAGE_SIZE:
            errors.append(f"LOAD segment {index} file/virtual offsets differ modulo {PAGE_SIZE}")
    if not load_count:
        raise ValueError("ELF contains no LOAD segments")
    return errors


def check_apk(path):
    failures = 0
    checked = 0
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        for member in archive.infolist():
            parts = member.filename.split("/")
            if (len(parts) != 3 or parts[0] != "lib" or
                    parts[1] not in ABIS or not parts[2].endswith(".so")):
                continue
            checked += 1
            errors = elf_errors(archive.read(member))
            # Stored libraries are mmap'ed directly from the APK, so their ZIP
            # data offset must also be page-aligned. Compressed ones are extracted.
            if member.compress_type == zipfile.ZIP_STORED:
                raw.seek(member.header_offset)
                header = raw.read(30)
                if len(header) != 30 or header[:4] != b"PK\x03\x04":
                    raise ValueError(f"{member.filename}: invalid ZIP local header")
                name_size, extra_size = struct.unpack_from("<HH", header, 26)
                data_offset = member.header_offset + 30 + name_size + extra_size
                if data_offset % PAGE_SIZE:
                    errors.append(f"ZIP data offset {data_offset} is not {PAGE_SIZE}-byte aligned")
            if errors:
                failures += 1
                for error in errors:
                    print(f"FAIL {path}: {member.filename}: {error}", file=sys.stderr)
            else:
                print(f"PASS {member.filename}")
    if not checked:
        raise ValueError(f"{path}: no supported 64-bit native libraries found")
    print(f"{path}: {checked} libraries checked, {failures} incompatible")
    return failures == 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()
    passed = True
    for path in args.apks:
        try:
            passed = check_apk(path) and passed
        except (OSError, ValueError, struct.error, zipfile.BadZipFile) as error:
            print(f"FAIL {path}: {error}", file=sys.stderr)
            passed = False
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
