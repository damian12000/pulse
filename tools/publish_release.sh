#!/usr/bin/env bash
# Publish the bundled databases as a GitHub release.
#
# Prerequisites:
#   1. gh installed          (winget install GitHub.cli)
#   2. gh authenticated      (gh auth login)   <- you must do this yourself
#   3. assets prepared       (python tools/prepare_release_assets.py)
#
# Usage:
#   bash tools/publish_release.sh [tag]
#
# Re-running with the same tag replaces the assets rather than erroring, so a
# rebuilt database can be republished without inventing a new tag.

set -euo pipefail

REPO="damian12000/pulse"
TAG="${1:-data-v1}"
RELEASE_DIR="data/release"

command -v gh >/dev/null 2>&1 || {
  echo "gh not found. Install: winget install GitHub.cli" >&2
  exit 1
}

gh auth status >/dev/null 2>&1 || {
  echo "Not authenticated. Run: gh auth login" >&2
  exit 1
}

for f in opennutrition.db.gz exercises.db.gz manifest.json LICENSE-DATA.txt; do
  [ -f "$RELEASE_DIR/$f" ] || {
    echo "Missing $RELEASE_DIR/$f — run: python tools/prepare_release_assets.py" >&2
    exit 1
  }
done

echo "Publishing $TAG to $REPO"
du -h "$RELEASE_DIR"/*.gz | sed 's/^/  /'

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "Release exists — replacing assets."
  gh release upload "$TAG" \
    "$RELEASE_DIR/opennutrition.db.gz" \
    "$RELEASE_DIR/exercises.db.gz" \
    "$RELEASE_DIR/manifest.json" \
    "$RELEASE_DIR/LICENSE-DATA.txt" \
    --repo "$REPO" --clobber
else
  gh release create "$TAG" \
    "$RELEASE_DIR/opennutrition.db.gz" \
    "$RELEASE_DIR/exercises.db.gz" \
    "$RELEASE_DIR/manifest.json" \
    "$RELEASE_DIR/LICENSE-DATA.txt" \
    --repo "$REPO" \
    --title "Bundled data $TAG" \
    --notes "$(cat <<'NOTES'
Bundled databases for PULSE, downloaded by the app on first run.

| Asset | Contents | Download |
|---|---|---|
| `opennutrition.db.gz` | 326,759 foods · 313,442 barcodes · 654,278 servings | 67.3 MB |
| `exercises.db.gz` | 873 exercises with muscles, equipment and instructions | 0.2 MB |

`manifest.json` carries the SHA-256 of each compressed asset; the app verifies
before committing anything to disk.

## Licence

These are **derived databases** distributed under the
[Open Database License (ODbL) v1.0](https://opendatacommons.org/licenses/odbl/1-0/).
Full terms and per-source attribution are in `LICENSE-DATA.txt`.

Sources: [OpenNutrition](https://www.opennutrition.app) (ODbL) ·
© [Open Food Facts](https://world.openfoodfacts.org) contributors (ODbL) ·
USDA FoodData Central (public domain) · Canadian Nutrient File (OGL–Canada) ·
Frida (Denmark) · AUSNUT (Australia) ·
[free-exercise-db](https://github.com/yuhonas/free-exercise-db) (Unlicense).

Rebuildable from source with the scripts in `tools/`.
NOTES
)"
fi

echo
echo "Done. Asset URLs:"
gh release view "$TAG" --repo "$REPO" --json assets \
  --jq '.assets[] | "  \(.name)  \(.url)"'
