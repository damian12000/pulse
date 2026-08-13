"""Inspect the OpenNutrition TSV to verify the assumptions in PHASE2_ARCHITECTURE.md §4.2.

Answers, with evidence rather than assumption:
  - What are the exact columns?
  - What is the JSON shape of `serving`, `nutrition_100g`, `package_size`?
  - Are nutrition values really per 100 g? What units and keys?
  - How many rows have an EAN-13 barcode? A usable serving? Complete macros?
  - Which nutrient keys exist, and how often?

Run:  python tools/inspect_opennutrition.py
"""

import csv
import json
import sys
from collections import Counter
from pathlib import Path

csv.field_size_limit(10_000_000)

TSV = Path(__file__).resolve().parent.parent / "data/raw/extracted/opennutrition_foods.tsv"

SAMPLE_LIMIT = 200_000  # rows to scan for statistics; None = whole file


def main() -> None:
    if not TSV.exists():
        sys.exit(f"Not found: {TSV}")

    with TSV.open("r", encoding="utf-8", newline="") as fh:
        reader = csv.DictReader(fh, delimiter="\t")
        columns = reader.fieldnames or []

        print("=" * 78)
        print("COLUMNS")
        print("=" * 78)
        for i, c in enumerate(columns):
            print(f"  {i:2d}. {c}")

        # --- structural samples -------------------------------------------------
        print()
        print("=" * 78)
        print("SAMPLE ROWS (first 3 with a barcode, first 2 without)")
        print("=" * 78)

        shown_barcode = 0
        shown_plain = 0
        nutrient_keys: Counter = Counter()
        serving_keys: Counter = Counter()
        pkg_keys: Counter = Counter()
        type_counts: Counter = Counter()
        source_counts: Counter = Counter()
        unit_samples: dict[str, Counter] = {}

        total = 0
        with_barcode = 0
        with_serving = 0
        with_macros = 0
        with_kcal = 0
        energy_mismatch = 0
        checked_energy = 0

        for row in reader:
            total += 1

            raw_ean = (row.get("ean_13") or "").strip()
            has_ean = bool(raw_ean) and raw_ean.lower() not in ("none", "null", "nan")
            if has_ean:
                with_barcode += 1

            type_counts[(row.get("type") or "").strip()] += 1

            # source is a JSON array
            try:
                srcs = json.loads(row.get("source") or "[]")
                if isinstance(srcs, list):
                    for s in srcs:
                        source_counts[
                            s if isinstance(s, str) else json.dumps(s, sort_keys=True)[:60]
                        ] += 1
                elif isinstance(srcs, dict):
                    source_counts[json.dumps(srcs, sort_keys=True)[:60]] += 1
            except (json.JSONDecodeError, TypeError):
                source_counts["<unparseable>"] += 1

            # nutrition_100g
            nut = {}
            try:
                nut = json.loads(row.get("nutrition_100g") or "{}")
            except (json.JSONDecodeError, TypeError):
                nut = {}
            if isinstance(nut, dict):
                for k, v in nut.items():
                    nutrient_keys[k] += 1
                    if isinstance(v, str):
                        unit_samples.setdefault(k, Counter())[v[:24]] += 1

            # serving
            srv = {}
            try:
                srv = json.loads(row.get("serving") or "{}")
            except (json.JSONDecodeError, TypeError):
                srv = {}
            if isinstance(srv, dict) and srv:
                with_serving += 1
                for k in srv:
                    serving_keys[k] += 1

            try:
                pkg = json.loads(row.get("package_size") or "{}")
                if isinstance(pkg, dict):
                    for k in pkg:
                        pkg_keys[k] += 1
            except (json.JSONDecodeError, TypeError):
                pass

            # completeness + energy sanity
            def num(key):
                v = nut.get(key) if isinstance(nut, dict) else None
                if isinstance(v, (int, float)):
                    return float(v)
                if isinstance(v, str):
                    try:
                        return float(v.split()[0])
                    except (ValueError, IndexError):
                        return None
                return None

            kcal = num("energy") or num("calories") or num("kcal")
            p, c, f = num("protein"), num("carbohydrates") or num("carbs"), num("fat")
            if kcal is not None:
                with_kcal += 1
            if None not in (p, c, f):
                with_macros += 1
                if kcal is not None and kcal > 0:
                    checked_energy += 1
                    calc = 4 * p + 4 * c + 9 * f
                    if abs(calc - kcal) / kcal > 0.25:
                        energy_mismatch += 1

            # print a few samples
            if has_ean and shown_barcode < 3:
                shown_barcode += 1
                dump_row(row, f"WITH BARCODE #{shown_barcode}")
            elif not has_ean and shown_plain < 2:
                shown_plain += 1
                dump_row(row, f"NO BARCODE #{shown_plain}")

            if SAMPLE_LIMIT and total >= SAMPLE_LIMIT:
                break

    # --- report ---------------------------------------------------------------
    print()
    print("=" * 78)
    print(f"STATISTICS  (scanned {total:,} rows"
          f"{' — SAMPLE, not whole file' if SAMPLE_LIMIT and total >= SAMPLE_LIMIT else ' — whole file'})")
    print("=" * 78)
    pct = lambda n: f"{n:,} ({100 * n / total:.1f}%)" if total else "0"
    print(f"  rows with EAN-13 barcode  : {pct(with_barcode)}")
    print(f"  rows with serving object  : {pct(with_serving)}")
    print(f"  rows with energy value    : {pct(with_kcal)}")
    print(f"  rows with all 3 macros    : {pct(with_macros)}")
    if checked_energy:
        print(f"  energy vs 4/4/9 mismatch  : {energy_mismatch:,} of {checked_energy:,} "
              f"checked ({100 * energy_mismatch / checked_energy:.1f}%) exceed 25%")

    print()
    print("  food `type` distribution:")
    for k, v in type_counts.most_common(10):
        print(f"    {k or '<empty>':<24} {v:,}")

    print()
    print("  `source` values (top 15):")
    for k, v in source_counts.most_common(15):
        print(f"    {k:<46} {v:,}")

    print()
    print(f"  nutrition_100g keys ({len(nutrient_keys)} distinct), top 40:")
    for k, v in nutrient_keys.most_common(40):
        sample = ""
        if k in unit_samples:
            sample = "  e.g. " + ", ".join(s for s, _ in unit_samples[k].most_common(2))
        print(f"    {k:<28} {v:>9,}{sample}")

    print()
    print(f"  serving keys ({len(serving_keys)} distinct):")
    for k, v in serving_keys.most_common(20):
        print(f"    {k:<28} {v:>9,}")

    print()
    print(f"  package_size keys ({len(pkg_keys)} distinct):")
    for k, v in pkg_keys.most_common(20):
        print(f"    {k:<28} {v:>9,}")


def dump_row(row: dict, title: str) -> None:
    print()
    print(f"--- {title} " + "-" * (72 - len(title)))
    for k, v in row.items():
        v = (v or "").strip()
        if k in ("serving", "nutrition_100g", "package_size", "source",
                 "alternate_names", "labels", "ingredient_analysis"):
            try:
                parsed = json.loads(v) if v else None
                v = json.dumps(parsed, ensure_ascii=False)
            except (json.JSONDecodeError, TypeError):
                pass
        if len(v) > 300:
            v = v[:300] + f" …[+{len(v) - 300} chars]"
        print(f"  {k:<20}: {v}")


if __name__ == "__main__":
    main()
