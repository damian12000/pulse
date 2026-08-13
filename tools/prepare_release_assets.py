"""Package the bundled databases for hosting as GitHub release assets.

Compresses each database and records the SHA-256 of the *compressed* file —
that is what the app downloads, so that is what it must verify before
committing anything to disk.

Emits a manifest the app can be pinned against, and a LICENSE-DATA.txt that
satisfies the attribution terms of the upstream datasets (see the licensing
note in docs/DATASET_FINDINGS.md §6).

Run:  python tools/prepare_release_assets.py
"""

from __future__ import annotations

import gzip
import hashlib
import json
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "data/build"
OUT = ROOT / "data/release"

ASSETS = ["opennutrition.db", "exercises.db"]

LICENSE_TEXT = """\
PULSE bundled data — licensing and attribution
==============================================

These database files are DERIVED DATABASES built from open datasets. They are
made available under the Open Database License (ODbL) v1.0:

    https://opendatacommons.org/licenses/odbl/1-0/

You are free to share, adapt and use them, including commercially, provided you
attribute the sources, keep any derivative database under ODbL, and do not use
technical measures to restrict others.

Sources
-------

opennutrition.db
    Built from the OpenNutrition Foods dataset.
    Attribution: "OpenNutrition" — https://www.opennutrition.app
    Licence: ODbL 1.0, contents under a modified DbCL 1.0.

    That dataset in turn incorporates data from Open Food Facts.
    Attribution: "(c) Open Food Facts contributors"
        https://world.openfoodfacts.org
    Licence: ODbL 1.0.

    It also incorporates public food composition data from:
      - USDA FoodData Central (public domain, CC0)
      - Canadian Nutrient File, Health Canada (Open Government Licence - Canada)
      - Frida, DTU National Food Institute (Denmark)
      - AUSNUT, Food Standards Australia New Zealand

exercises.db
    Built from free-exercise-db — https://github.com/yuhonas/free-exercise-db
    Dataset released into the public domain (Unlicense).

Modifications
-------------

Records were filtered, normalised to per-100 g units, de-duplicated, indexed for
full-text search, and reduced to the fields PULSE uses. Nutrition values are
unchanged from source. No new nutritional data was created.

Build tooling that reproduces these files from the original sources is in
tools/ in the PULSE repository.
"""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    missing = [name for name in ASSETS if not (BUILD / name).exists()]
    if missing:
        sys.exit(
            "Not built: " + ", ".join(missing) + "\n"
            "Run: python tools/build_food_db.py --lean\n"
            "     python tools/build_exercise_db.py\n"
            "     python tools/stamp_room_identity.py"
        )

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "LICENSE-DATA.txt").write_text(LICENSE_TEXT, encoding="utf-8")

    manifest: dict[str, dict] = {
        "schemaVersion": 1,
        "generatedAt": int(time.time()),
        "assets": {},
    }

    print("=" * 74)
    print("RELEASE ASSETS")
    print("=" * 74)

    for name in ASSETS:
        src = BUILD / name
        gz = OUT / f"{name}.gz"

        print(f"\n  {name}")
        print(f"    raw          {src.stat().st_size / 1048576:>8,.1f} MB")

        # Room verifies the identity hash inside the file, so the file must be
        # stamped before compression — compressing an unstamped database ships
        # something the app will refuse to open.
        with src.open("rb") as fin, gzip.open(gz, "wb", compresslevel=9) as fout:
            shutil.copyfileobj(fin, fout, length=1 << 20)

        raw_sha = sha256(src)
        gz_sha = sha256(gz)

        print(f"    compressed   {gz.stat().st_size / 1048576:>8,.1f} MB "
              f"({100 * gz.stat().st_size / src.stat().st_size:.0f}% of raw)")
        print(f"    sha256(gz)   {gz_sha}")

        manifest["assets"][name] = {
            "file": gz.name,
            "compressedBytes": gz.stat().st_size,
            "compressedSha256": gz_sha,
            "uncompressedBytes": src.stat().st_size,
            "uncompressedSha256": raw_sha,
        }

    (OUT / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )

    total = sum(a["compressedBytes"] for a in manifest["assets"].values())
    print()
    print("=" * 74)
    print(f"  wrote {OUT.relative_to(ROOT)}/")
    for f in sorted(OUT.iterdir()):
        print(f"    {f.name:<28} {f.stat().st_size / 1048576:>8,.1f} MB")
    print(f"\n  total download for a new install: {total / 1048576:,.1f} MB")
    print("\n  Upload the .gz files, manifest.json and LICENSE-DATA.txt as")
    print("  release assets, then pin the URLs and checksums in the app.")


if __name__ == "__main__":
    main()
