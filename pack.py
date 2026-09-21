#!/usr/bin/env python3
"""Package and verify the APK the way `zipalign` would.

Why this exists: there is no `zipalign` binary on this device, and naively re-zipping with
zipfile re-compresses every entry. Android 11+ requires `resources.arsc` to be **uncompressed**
and 4-byte aligned (and `AndroidManifest.xml` uncompressed), otherwise the package installer
rejects the app - on MIUI as a vague "该安装包与您的系统不兼容" / error -124.

Usage:
    pack.py build  --base BASE.apk --dex classes.dex [--dex ...] --out OUT.apk
    pack.py verify --apk SOME.apk
"""

import argparse
import struct
import sys
import zipfile

# Entries that must be stored uncompressed for the platform to accept the APK.
REQUIRED_STORED = ("resources.arsc", "AndroidManifest.xml")


def alignment_extra(offset, name_len):
    """Extra-field bytes that put this entry's data on a 4-byte boundary.

    A zip extra field must be a sequence of (id, size) records, so when padding is needed we
    emit a 4-byte header plus the padding payload, keeping the total a valid record.
    """
    pad = (4 - (offset + 30 + name_len) % 4) % 4
    if pad == 0:
        return b""
    total = pad + 4
    return struct.pack("<HH", 0x0000, total - 4) + b"\x00" * (total - 4)


def data_offset(info):
    return info.header_offset + 30 + len(info.filename.encode("utf-8")) + len(info.extra)


def build(base_apk, dex_files, out_apk, align_all=True):
    items = []
    with zipfile.ZipFile(base_apk) as zin:
        for info in zin.infolist():
            items.append((info.filename, info.date_time, info.external_attr, zin.read(info.filename)))
    for name, path in dex_files:
        with open(path, "rb") as handle:
            items.append((name, (2026, 1, 1, 0, 0, 0), 0o644 << 16, handle.read()))

    with zipfile.ZipFile(out_apk, "w") as zout:
        for name, date_time, external_attr, data in items:
            info = zipfile.ZipInfo(name, date_time)
            store = name in REQUIRED_STORED
            info.compress_type = zipfile.ZIP_STORED if store else zipfile.ZIP_DEFLATED
            info.external_attr = external_attr
            if store or align_all:
                info.extra = alignment_extra(zout.fp.tell(), len(name.encode("utf-8")))
            else:
                info.extra = b""
            zout.writestr(info, data)
    return out_apk


def verify(apk):
    problems = []
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        for required in REQUIRED_STORED:
            if required not in names:
                continue
            info = z.getinfo(required)
            if info.compress_type != zipfile.ZIP_STORED:
                problems.append("%s is compressed (must be STORED)" % required)
            if data_offset(info) % 4 != 0:
                problems.append("%s is not 4-byte aligned (offset mod 4 = %d)"
                                % (required, data_offset(info) % 4))
        if "classes.dex" not in names:
            problems.append("classes.dex is missing")
        if "AndroidManifest.xml" not in names:
            problems.append("AndroidManifest.xml is missing")

    if problems:
        print("APK packaging check FAILED:")
        for p in problems:
            print("  - " + p)
        return 1
    print("APK packaging check passed: resources.arsc and AndroidManifest.xml are stored and 4-byte aligned")
    return 0


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build")
    b.add_argument("--base", required=True)
    b.add_argument("--dex", action="append", default=[])
    b.add_argument("--out", required=True)

    v = sub.add_parser("verify")
    v.add_argument("--apk", required=True)

    args = parser.parse_args()
    if args.cmd == "build":
        dex_files = [(name.split("=", 1)[0], name.split("=", 1)[1]) if "=" in name else
                     (name.rsplit("/", 1)[-1], name) for name in args.dex]
        build(args.base, dex_files, args.out)
        print("packaged", args.out)
        return 0
    return verify(args.apk)


if __name__ == "__main__":
    sys.exit(main())
