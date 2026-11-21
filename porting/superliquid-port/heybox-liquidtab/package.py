"""Extract or replace the personal HyperChanger APK's relocated SuperLiquid DEX."""

import argparse
from copy import copy
from hashlib import sha256
from pathlib import Path
from zipfile import ZipFile

SECONDARY = "classes2.dex"
STABLE = "4e1ca6e3e595d6ddbd4a79430c47a4330f2f5088406e5115bc7bad2710bbcc97"
LIQUIDTAB = "63024d2c9dfe8cdc3d23c58fb7c983ae600a0163565bd2130e49594771c837b9"
HELPER = b"Lbtm/m/ported/superliquid/io/github/liuran001/mmliquidglass/HeyboxGlassMaterial;"


def signing_entry(name):
    return (
        name == "META-INF/MANIFEST.MF"
        or name.startswith("META-INF/") and name.endswith((".SF", ".RSA", ".EC", ".DSA"))
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--rebuilt-secondary", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("The output file already exists.")

    with ZipFile(args.base) as base:
        secondary = base.read(SECONDARY)
        digest = sha256(secondary).hexdigest()
        if digest not in (STABLE, LIQUIDTAB):
            parser.error("The base APK does not have a verified Heybox payload.")
        if args.rebuilt_secondary is None:
            with ZipFile(args.output, "x") as output:
                output.writestr("AndroidManifest.xml", base.read("AndroidManifest.xml"))
                output.writestr("classes.dex", secondary)
            print("Extracted the existing LiquidTab port." if digest == LIQUIDTAB else
                  "Extracted the stable Heybox port. Apply heybox-liquidtab.patch before rebuilding.")
            return

        with ZipFile(args.rebuilt_secondary) as rebuilt:
            updated = rebuilt.read("classes.dex")
        if updated == secondary or HELPER not in updated:
            parser.error("The replacement DEX does not contain the updated Heybox material.")
        with ZipFile(args.output, "x") as output:
            for entry in base.infolist():
                if signing_entry(entry.filename):
                    continue
                contents = updated if entry.filename == SECONDARY else base.read(entry.filename)
                output.writestr(copy(entry), contents)
        with ZipFile(args.output) as output:
            changed = {name for name in output.namelist() if output.read(name) != base.read(name)}
            if changed != {SECONDARY}:
                raise ValueError(f"Unexpected APK changes: {changed}")
        print("Updated only the relocated SuperLiquid DEX. Align and sign the APK before installing.")


if __name__ == "__main__":
    main()
