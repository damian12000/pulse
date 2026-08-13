"""Build the bundled PULSE food database from the OpenNutrition TSV.

Produces a SQLite file whose `food` / `food_serving` / `food_fts` tables match
the Room entities in :core:database, ready to ship via Room's
`createFromAsset()` / `createFromFile()`.

Field mapping and data-quality rules are documented in docs/DATASET_FINDINGS.md
and were verified against the full 326,759-row file — not sampled.

Usage:
    python tools/build_food_db.py                    # full dataset
    python tools/build_food_db.py --limit 5000       # quick dev build
    python tools/build_food_db.py --no-micros        # drop micronutrient JSON (smaller)

Note: Room verifies a schema identity hash stored in `room_master_table`. That
hash is only known once the Room entities compile and export their schema JSON
(build/schemas/*.json). Run `stamp_room_identity.py` after the first successful
build to stamp it; until then Room will reject the asset with a mismatch error.
That is expected and is a two-minute step, not a redesign.
"""

from __future__ import annotations

import argparse
import csv
import json
import sqlite3
import sys
import time
from pathlib import Path

csv.field_size_limit(10_000_000)

ROOT = Path(__file__).resolve().parent.parent
TSV = ROOT / "data/raw/extracted/opennutrition_foods.tsv"
OUT = ROOT / "data/build/opennutrition.db"

SOURCE = "OPENNUTRITION"

# Verified mapping — see docs/DATASET_FINDINGS.md §2
NUTRIENT_MAP = {
    "calories": "kcalPer100",
    "protein": "proteinPer100",
    "carbohydrates": "carbsPer100",
    "total_fat": "fatPer100",
    "dietary_fiber": "fiberPer100",
    "total_sugars": "sugarPer100",
    "saturated_fats": "satFatPer100",
    "sodium": "sodiumMgPer100",
    "cholesterol": "cholesterolMgPer100",
    "potassium": "potassiumMgPer100",
}
# The dataset carries 97 nutrient keys. Storing all of them costs ~540 MB across
# the full file (measured), which dwarfs everything else. Store a curated subset
# that a fitness app would plausibly surface; the rest is dropped and can be
# re-imported from the source TSV if ever needed.
MICRO_KEEP = {
    "trans_fats", "added_sugars", "soluble_fiber", "insoluble_fiber",
    "sugar_alcohols", "monounsaturated_fats", "polyunsaturated_fats",
    "omega_3", "omega_6",
    "calcium", "iron", "magnesium", "zinc", "phosphorus", "selenium",
    "vitamin_a", "vitamin_c", "vitamin_d", "vitamin_e", "vitamin_k",
    "vitamin_b6", "vitamin_b12", "folate", "thiamin", "riboflavin", "niacin",
    "caffeine", "water", "alcohol",
}

REQUIRED = ("calories", "protein", "carbohydrates", "total_fat")

MAX_KCAL_PER_100G = 900.0  # pure fat ~900; anything above is impossible (§4.6)

SCHEMA = """
PRAGMA journal_mode = OFF;
PRAGMA synchronous  = OFF;

CREATE TABLE IF NOT EXISTS food (
    id                   TEXT    NOT NULL PRIMARY KEY,
    source               TEXT    NOT NULL,
    sourceId             TEXT,
    derivedFromFoodId    TEXT,
    barcode              TEXT,
    name                 TEXT    NOT NULL,
    brand                TEXT,
    alternateNames       TEXT,
    foodType             TEXT,
    kcalPer100           REAL    NOT NULL,
    proteinPer100        REAL    NOT NULL,
    carbsPer100          REAL    NOT NULL,
    fatPer100            REAL    NOT NULL,
    fiberPer100          REAL,
    sugarPer100          REAL,
    satFatPer100         REAL,
    sodiumMgPer100       REAL,
    cholesterolMgPer100  REAL,
    potassiumMgPer100    REAL,
    micronutrientsJson   TEXT,
    isLiquid             INTEGER NOT NULL DEFAULT 0,
    densityGPerMl        REAL,
    ingredients          TEXT,
    allergensJson        TEXT,
    imageUrl             TEXT,
    localImagePath       TEXT,
    dataConfidence       TEXT    NOT NULL,
    recipeId             TEXT,
    createdAt            INTEGER NOT NULL,
    updatedAt            INTEGER NOT NULL,
    isDeleted            INTEGER NOT NULL DEFAULT 0,
    syncState            TEXT    NOT NULL DEFAULT 'SYNCED'
);

CREATE TABLE IF NOT EXISTS food_serving (
    id          TEXT    NOT NULL PRIMARY KEY,
    foodId      TEXT    NOT NULL,
    label       TEXT    NOT NULL,
    gramWeight  REAL    NOT NULL,
    isDefault   INTEGER NOT NULL DEFAULT 0,
    sortOrder   INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (foodId) REFERENCES food(id) ON DELETE CASCADE
);
"""

INDEXES = """
CREATE INDEX IF NOT EXISTS index_food_barcode        ON food(barcode);
CREATE INDEX IF NOT EXISTS index_food_source         ON food(source);
CREATE INDEX IF NOT EXISTS index_food_name           ON food(name);
CREATE INDEX IF NOT EXISTS index_food_foodType       ON food(foodType);
CREATE INDEX IF NOT EXISTS index_food_serving_foodId ON food_serving(foodId);
"""

# FTS over name + brand + alternateNames (§4.2). Room maps this to @Fts4.
FTS = """
CREATE VIRTUAL TABLE IF NOT EXISTS food_fts USING fts4(
    name, brand, alternateNames, content=`food`, tokenize=unicode61
);
INSERT INTO food_fts(docid, name, brand, alternateNames)
    SELECT rowid, name, brand, alternateNames FROM food;
INSERT INTO food_fts(food_fts) VALUES('optimize');
"""


def as_float(v):
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str):
        try:
            return float(v.split()[0])
        except (ValueError, IndexError):
            return None
    return None


def load_json(raw, default):
    if not raw:
        return default
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return default
    return parsed if parsed is not None else default


def split_brand(name: str) -> tuple[str, str | None]:
    """OpenNutrition encodes brand in the name as '<product> by <Brand>'."""
    marker = " by "
    idx = name.rfind(marker)
    if idx > 0:
        product, brand = name[:idx].strip(), name[idx + len(marker):].strip()
        # guard against product names that legitimately contain ' by '
        if product and brand and len(brand) <= 60:
            return product, brand
    return name, None


def serving_label(common: dict, metric: dict) -> str:
    unit = (common.get("unit") or metric.get("unit") or "serving").strip()
    qty = as_float(common.get("quantity")) or as_float(metric.get("quantity")) or 1.0
    qty_str = f"{qty:g}"
    return f"{qty_str} {unit}".strip()


def confidence(kcal: float, p: float, c: float, f: float) -> str:
    """Phase 2 §6.3 rule, calibrated in DATASET_FINDINGS.md §5."""
    if kcal > MAX_KCAL_PER_100G:
        return "LOW"
    if kcal <= 0:
        return "MEDIUM" if (p or c or f) else "LOW"
    calc = 4 * p + 4 * c + 9 * f
    diff = abs(calc - kcal) / kcal
    if diff <= 0.10:
        return "HIGH"
    if diff <= 0.25:
        return "MEDIUM"
    return "LOW"


def build(limit: int | None, keep_micros: bool, lean: bool = False) -> None:
    if not TSV.exists():
        sys.exit(f"Missing dataset: {TSV}\nRun the download step first.")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    if OUT.exists():
        OUT.unlink()

    now = int(time.time() * 1000)
    conn = sqlite3.connect(OUT)
    conn.executescript(SCHEMA)

    foods: list[tuple] = []
    servings: list[tuple] = []
    stats = {
        "read": 0, "written": 0, "skipped_missing_macros": 0,
        "with_barcode": 0, "liquid": 0, "servings": 0,
        "HIGH": 0, "MEDIUM": 0, "LOW": 0, "kcal_clamped": 0,
    }

    # Explicit column list — positional inserts silently rot when the schema grows.
    FOOD_COLS = [
        "id", "source", "sourceId", "derivedFromFoodId", "barcode",
        "name", "brand", "alternateNames", "foodType",
        "kcalPer100", "proteinPer100", "carbsPer100", "fatPer100",
        "fiberPer100", "sugarPer100", "satFatPer100",
        "sodiumMgPer100", "cholesterolMgPer100", "potassiumMgPer100",
        "micronutrientsJson", "isLiquid", "densityGPerMl",
        "ingredients", "allergensJson", "imageUrl", "localImagePath",
        "dataConfidence", "recipeId",
        "createdAt", "updatedAt", "isDeleted", "syncState",
    ]
    food_sql = (
        f"INSERT OR REPLACE INTO food ({','.join(FOOD_COLS)}) "
        f"VALUES ({','.join('?' * len(FOOD_COLS))})"
    )

    def flush():
        if foods:
            conn.executemany(food_sql, foods)
            foods.clear()
        if servings:
            conn.executemany(
                "INSERT OR REPLACE INTO food_serving VALUES (?,?,?,?,?,?)", servings
            )
            servings.clear()

    with TSV.open("r", encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh, delimiter="\t"):
            stats["read"] += 1

            nut = load_json(row.get("nutrition_100g"), {})
            if not isinstance(nut, dict):
                nut = {}

            vals = {k: as_float(nut.get(k)) for k in NUTRIENT_MAP}
            if any(vals[k] is None for k in REQUIRED):
                stats["skipped_missing_macros"] += 1
                continue

            kcal = vals["calories"]
            p, c, f = vals["protein"], vals["carbohydrates"], vals["total_fat"]
            conf = confidence(kcal, p, c, f)
            if kcal > MAX_KCAL_PER_100G:
                stats["kcal_clamped"] += 1
            stats[conf] += 1

            micros = None
            if keep_micros:
                rest = {
                    k: v for k, v in nut.items()
                    if k in MICRO_KEEP and k not in NUTRIENT_MAP and v not in (None, 0, "0")
                }
                if rest:
                    micros = json.dumps(rest, separators=(",", ":"), ensure_ascii=False)

            food_id = (row.get("id") or "").strip()
            raw_name = (row.get("name") or "").strip()
            if not food_id or not raw_name:
                stats["skipped_missing_macros"] += 1
                continue
            name, brand = split_brand(raw_name)

            alts = load_json(row.get("alternate_names"), [])
            alt_text = " ".join(a for a in alts if isinstance(a, str)) if isinstance(alts, list) else None

            barcode = (row.get("ean_13") or "").strip()
            if barcode.lower() in ("none", "null", "nan"):
                barcode = ""
            if barcode:
                stats["with_barcode"] += 1

            srv = load_json(row.get("serving"), {})
            metric = srv.get("metric") or {} if isinstance(srv, dict) else {}
            common = srv.get("common") or {} if isinstance(srv, dict) else {}
            metric_qty = as_float(metric.get("quantity"))
            metric_unit = (metric.get("unit") or "").lower()
            is_liquid = 1 if metric_unit == "ml" else 0
            if is_liquid:
                stats["liquid"] += 1

            if lean:
                ingredients_text = None
                allergens = None
            else:
                ingredients_text = (row.get("ingredients") or "").strip() or None
                analysis = load_json(row.get("ingredient_analysis"), {})
                allergens = (
                    json.dumps(analysis, separators=(",", ":"), ensure_ascii=False)
                    if isinstance(analysis, dict) and analysis else None
                )

            foods.append((
                food_id, SOURCE, food_id, None, barcode or None,
                name, brand, alt_text, (row.get("type") or "").strip() or None,
                kcal, p, c, f,
                vals["dietary_fiber"], vals["total_sugars"], vals["saturated_fats"],
                vals["sodium"], vals["cholesterol"], vals["potassium"],
                micros, is_liquid, None,
                ingredients_text, allergens,
                None, None, conf, None,
                now, now, 0, "SYNCED",
            ))

            order = 0
            canonical_label = "100 ml" if is_liquid else "100 g"
            has_canonical = False

            if metric_qty and metric_qty > 0:
                # If the default serving IS 100 g/ml, use the canonical label for it
                # rather than emitting a second, duplicate 100-unit serving.
                if abs(metric_qty - 100.0) < 1e-9:
                    label, has_canonical = canonical_label, True
                else:
                    label = serving_label(common, metric)
                servings.append((f"{food_id}_s0", food_id, label, metric_qty, 1, order))
                stats["servings"] += 1
                order += 1

            if not has_canonical:
                servings.append((
                    f"{food_id}_s{order}", food_id, canonical_label, 100.0,
                    1 if order == 0 else 0, order,
                ))
                stats["servings"] += 1
                order += 1

            pkg = load_json(row.get("package_size"), {})
            pkg_metric = pkg.get("metric") or {} if isinstance(pkg, dict) else {}
            pkg_qty = as_float(pkg_metric.get("quantity"))
            # Skip a package serving that duplicates an existing one (commonly 100 g).
            existing = {round(s[3], 6) for s in servings if s[1] == food_id}
            if pkg_qty and pkg_qty > 0 and round(pkg_qty, 6) not in existing:
                servings.append((
                    f"{food_id}_s{order}", food_id, "1 package", pkg_qty, 0, order,
                ))
                stats["servings"] += 1

            stats["written"] += 1

            if len(foods) >= 5000:
                flush()
            if stats["read"] % 50_000 == 0:
                print(f"  … {stats['read']:,} read / {stats['written']:,} written", file=sys.stderr)
            if limit and stats["written"] >= limit:
                break

    flush()
    print("  building indexes …", file=sys.stderr)
    conn.executescript(INDEXES)
    print("  building FTS index …", file=sys.stderr)
    conn.executescript(FTS)
    conn.commit()
    print("  vacuum …", file=sys.stderr)
    conn.execute("VACUUM")
    conn.close()

    size_mb = OUT.stat().st_size / (1024 * 1024)
    print()
    print("=" * 66)
    print(f"BUILT  {OUT.relative_to(ROOT)}")
    print("=" * 66)
    print(f"  rows read              {stats['read']:,}")
    print(f"  foods written          {stats['written']:,}")
    print(f"  skipped (bad/missing)  {stats['skipped_missing_macros']:,}")
    print(f"  with barcode           {stats['with_barcode']:,}")
    print(f"  liquids                {stats['liquid']:,}")
    print(f"  serving rows           {stats['servings']:,}")
    print(f"  confidence  HIGH       {stats['HIGH']:,}")
    print(f"              MEDIUM     {stats['MEDIUM']:,}")
    print(f"              LOW        {stats['LOW']:,}")
    print(f"  kcal>900 flagged LOW   {stats['kcal_clamped']:,}")
    print(f"  file size              {size_mb:,.1f} MB")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--no-micros", action="store_true")
    ap.add_argument("--lean", action="store_true",
                    help="omit ingredients + allergen analysis (~61%% of text)")
    a = ap.parse_args()
    build(a.limit, keep_micros=not a.no_micros, lean=a.lean)
