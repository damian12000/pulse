"""Full-file validation of the OpenNutrition dataset against the PULSE schema
(PHASE2_ARCHITECTURE.md §4.2). Confirms field mapping and data quality before
the importer is written.

Run:  python tools/validate_opennutrition.py
"""

import csv
import json
import sys
from collections import Counter
from pathlib import Path

csv.field_size_limit(10_000_000)
TSV = Path(__file__).resolve().parent.parent / "data/raw/extracted/opennutrition_foods.tsv"

# Verified key mapping: OpenNutrition -> PULSE `food` column
MACROS = {
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


def f(v):
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str):
        try:
            return float(v.split()[0])
        except (ValueError, IndexError):
            return None
    return None


def main() -> None:
    total = 0
    have = Counter()
    ean_present = ean_valid13 = ean_dup = 0
    seen_ean: set[str] = set()

    serving_ok = serving_ml = serving_g = 0
    common_units: Counter = Counter()

    complete = 0          # kcal + 3 macros
    energy_checked = energy_bad = energy_close = 0
    kcal_over_900 = neg_values = 0
    types: Counter = Counter()
    types_with_ean: Counter = Counter()

    with TSV.open("r", encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh, delimiter="\t"):
            total += 1
            types[(row.get("type") or "").strip()] += 1

            ean = (row.get("ean_13") or "").strip()
            if ean and ean.lower() not in ("none", "null", "nan"):
                ean_present += 1
                types_with_ean[(row.get("type") or "").strip()] += 1
                if len(ean) == 13 and ean.isdigit():
                    ean_valid13 += 1
                if ean in seen_ean:
                    ean_dup += 1
                else:
                    seen_ean.add(ean)

            try:
                nut = json.loads(row.get("nutrition_100g") or "{}")
            except (json.JSONDecodeError, TypeError):
                nut = {}
            if not isinstance(nut, dict):
                nut = {}

            vals = {}
            for src, dst in MACROS.items():
                v = f(nut.get(src))
                if v is not None:
                    have[src] += 1
                    vals[src] = v
                    if v < 0:
                        neg_values += 1

            kcal = vals.get("calories")
            p, c, ft = vals.get("protein"), vals.get("carbohydrates"), vals.get("total_fat")
            if None not in (kcal, p, c, ft):
                complete += 1
                if kcal > 900:
                    kcal_over_900 += 1
                if kcal > 0:
                    energy_checked += 1
                    calc = 4 * p + 4 * c + 9 * ft
                    diff = abs(calc - kcal) / kcal
                    if diff <= 0.10:
                        energy_close += 1
                    elif diff > 0.25:
                        energy_bad += 1

            try:
                srv = json.loads(row.get("serving") or "{}")
            except (json.JSONDecodeError, TypeError):
                srv = {}
            if isinstance(srv, dict):
                m = srv.get("metric") or {}
                cm = srv.get("common") or {}
                q, u = f(m.get("quantity")), (m.get("unit") or "").lower()
                if q and q > 0:
                    serving_ok += 1
                    if u == "ml":
                        serving_ml += 1
                    elif u == "g":
                        serving_g += 1
                if cm.get("unit"):
                    common_units[str(cm["unit"])[:20]] += 1

            if total % 100_000 == 0:
                print(f"  … {total:,} rows", file=sys.stderr)

    pc = lambda n: f"{n:>9,} ({100 * n / total:5.1f}%)" if total else "0"
    print("=" * 74)
    print(f"OPENNUTRITION FULL-FILE VALIDATION — {total:,} rows")
    print("=" * 74)

    print("\nBARCODES")
    print(f"  present                 {pc(ean_present)}")
    print(f"  valid 13-digit numeric  {pc(ean_valid13)}")
    print(f"  duplicate barcodes      {ean_dup:,}  (distinct: {len(seen_ean):,})")

    print("\nFIELD COVERAGE  (OpenNutrition key -> PULSE column)")
    for src, dst in MACROS.items():
        print(f"  {src:<22} -> {dst:<22} {pc(have[src])}")

    print("\nCOMPLETENESS & SANITY")
    print(f"  kcal + all 3 macros     {pc(complete)}")
    if energy_checked:
        print(f"  energy check performed  {energy_checked:,}")
        print(f"    within 10% (HIGH)     {energy_close:,} ({100*energy_close/energy_checked:.1f}%)")
        print(f"    beyond 25% (LOW)      {energy_bad:,} ({100*energy_bad/energy_checked:.1f}%)")
    print(f"  kcal/100g > 900 (impossible) {kcal_over_900:,}")
    print(f"  negative nutrient values     {neg_values:,}")

    print("\nSERVINGS")
    print(f"  usable metric serving   {pc(serving_ok)}")
    print(f"    unit = g              {serving_g:,}")
    print(f"    unit = ml (LIQUID)    {serving_ml:,}")
    print("  top common units:")
    for u, n in common_units.most_common(15):
        print(f"    {u:<22} {n:>9,}")

    print("\nTYPE DISTRIBUTION (all / with barcode)")
    for t, n in types.most_common():
        print(f"  {t or '<empty>':<14} {n:>9,} / {types_with_ean[t]:>9,}")


if __name__ == "__main__":
    main()
