#!/usr/bin/env python3
"""Fails when an APK has a class whose superclass or interface is package-private in another package.

ART refuses to load such a class with IllegalAccessError, so the app dies at launch. R8 has produced
this by moving only some classes of a package-private hierarchy (see app/src/main/keepRules).
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

apk, dexdump = sys.argv[1], sys.argv[2]
classes = {}
with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as archive:
    for name in archive.namelist():
        if not re.fullmatch(r"classes\d*\.dex", name):
            continue
        dump = subprocess.run([dexdump, archive.extract(name, tmp)], capture_output=True, text=True,
                              errors="replace", check=True).stdout
        for block in dump.split("Class #")[1:]:
            header = block.split("Static fields")[0]
            descriptor = re.search(r"Class descriptor\s*:\s*'(L[^']+;)'", header)
            flags = re.search(r"Access flags\s*:\s*0x([0-9a-f]+)", header)
            parents = re.findall(r"Superclass\s*:\s*'(L[^']+;)'", header)
            if "Interfaces" in header:
                parents += re.findall(r"#\d+\s*:\s*'(L[^']+;)'", header.split("Interfaces")[1])
            if descriptor and flags:
                classes[descriptor.group(1)] = (int(flags.group(1), 16), parents)


def package(descriptor):
    return descriptor[1:-1].rpartition("/")[0]


broken = [
    f"{name} -> {parent}"
    for name, (_, parents) in classes.items()
    for parent in parents
    if parent in classes and not classes[parent][0] & 0x1 and package(parent) != package(name)
]
for line in broken:
    print(f"Inaccessible parent class: {line}", file=sys.stderr)
sys.exit(1 if broken else 0)
