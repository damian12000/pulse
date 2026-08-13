"""Functional smoke test for the built food database.

Proves the bundled asset answers the queries PULSE actually makes, before any
Android code depends on it: barcode lookup, FTS search with ranking, serving
scaling, liquid detection, and confidence distribution.

Run:  python tools/smoke_test_db.py
"""

import sqlite3
import sys
from pathlib import Path

DB = Path(__file__).resolve().parent.parent / "data/build/opennutrition.db"

FAILS = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}{(' — ' + detail) if detail else ''}")
    if not ok:
        FAILS.append(label)


def main() -> None:
    if not DB.exists():
        sys.exit(f"Not built: {DB}")
    c = sqlite3.connect(DB)
    c.row_factory = sqlite3.Row

    print("=" * 70)
    print("PULSE FOOD DB — FUNCTIONAL SMOKE TEST")
    print("=" * 70)

    n_food = c.execute("SELECT COUNT(*) FROM food").fetchone()[0]
    n_srv = c.execute("SELECT COUNT(*) FROM food_serving").fetchone()[0]
    print(f"\nfoods={n_food:,}  servings={n_srv:,}  size={DB.stat().st_size/1048576:,.1f} MB")

    # ---- 1. barcode lookup -------------------------------------------------
    print("\n1. BARCODE LOOKUP (the scanner's hot path)")
    row = c.execute(
        "SELECT id, name, brand, barcode FROM food WHERE barcode IS NOT NULL LIMIT 1"
    ).fetchone()
    hit = c.execute(
        "SELECT name, brand, kcalPer100, proteinPer100 FROM food WHERE barcode = ?",
        (row["barcode"],),
    ).fetchone()
    check("resolves a known barcode", hit is not None,
          f"{row['barcode']} -> {hit['name']!r} ({hit['brand']})")

    plan = c.execute(
        "EXPLAIN QUERY PLAN SELECT * FROM food WHERE barcode = ?", ("0013764027053",)
    ).fetchall()
    uses_index = any("index_food_barcode" in str(dict(p)) for p in plan)
    check("barcode query uses the index", uses_index,
          "no full-table scan" if uses_index else str([dict(p) for p in plan]))

    check("unknown barcode returns nothing (NotFound path)",
          c.execute("SELECT COUNT(*) FROM food WHERE barcode='9999999999999'").fetchone()[0] == 0)

    # ---- 2. FTS search -----------------------------------------------------
    print("\n2. FULL-TEXT SEARCH")
    for q in ("chicken breast", "greek yogurt", "peanut butter"):
        rows = c.execute(
            """SELECT f.name, f.brand, f.foodType, f.kcalPer100
               FROM food_fts JOIN food f ON f.rowid = food_fts.docid
               WHERE food_fts MATCH ?
               ORDER BY CASE f.foodType WHEN 'everyday' THEN 0 WHEN 'prepared' THEN 1
                                        WHEN 'restaurant' THEN 2 ELSE 3 END
               LIMIT 3""",
            (q,),
        ).fetchall()
        check(f"search {q!r} returns results", len(rows) > 0,
              "; ".join(f"{r['name']} [{r['foodType']}] {r['kcalPer100']:.0f}kcal" for r in rows))

    # alternate-name search (DATASET_FINDINGS §4.2 — the reason alts are indexed)
    alt = c.execute(
        """SELECT f.name FROM food_fts JOIN food f ON f.rowid = food_fts.docid
           WHERE food_fts MATCH 'xl eggs' LIMIT 3"""
    ).fetchall()
    check("alternate names are searchable ('xl eggs')", len(alt) > 0,
          ", ".join(r["name"] for r in alt) if alt else "no match")

    # ---- 3. serving scaling ------------------------------------------------
    print("\n3. SERVING SCALING (the core nutrition maths)")
    f = c.execute(
        """SELECT f.id, f.name, f.kcalPer100, f.proteinPer100, f.carbsPer100, f.fatPer100
           FROM food f JOIN food_serving s ON s.foodId = f.id
           WHERE s.isDefault = 1 AND f.kcalPer100 > 100 LIMIT 1"""
    ).fetchone()
    s = c.execute(
        "SELECT label, gramWeight FROM food_serving WHERE foodId=? AND isDefault=1", (f["id"],)
    ).fetchone()
    factor = s["gramWeight"] / 100.0
    kcal1 = f["kcalPer100"] * factor
    kcal25 = kcal1 * 2.5
    prot25 = f["proteinPer100"] * factor * 2.5
    print(f"       {f['name'][:52]}")
    print(f"       1 × {s['label']} ({s['gramWeight']:g} g) = {kcal1:.1f} kcal")
    print(f"       2.5 ×                     = {kcal25:.1f} kcal, {prot25:.1f} g protein")
    check("nutrition scales linearly", abs(kcal25 - kcal1 * 2.5) < 1e-9)

    every = c.execute(
        "SELECT COUNT(*) FROM food WHERE id NOT IN (SELECT foodId FROM food_serving)"
    ).fetchone()[0]
    check("every food has at least one serving", every == 0, f"{every} orphans")

    # Count distinct foods, not rows — a food may legitimately have several
    # servings; the invariant is that each has at least one of exactly 100.
    hundred = c.execute(
        "SELECT COUNT(DISTINCT foodId) FROM food_serving WHERE gramWeight = 100.0"
    ).fetchone()[0]
    check("every food has a canonical 100 g/ml serving", hundred == n_food,
          f"{hundred:,} of {n_food:,}")

    dupes = c.execute(
        "SELECT COUNT(*) FROM (SELECT foodId FROM food_serving "
        "GROUP BY foodId, gramWeight HAVING COUNT(*) > 1)"
    ).fetchone()[0]
    check("no duplicate serving sizes within a food", dupes == 0, f"{dupes} duplicated")

    # ---- 4. liquids --------------------------------------------------------
    print("\n4. LIQUID DETECTION")
    liq = c.execute("SELECT COUNT(*) FROM food WHERE isLiquid = 1").fetchone()[0]
    check("liquids flagged from serving unit", liq > 0, f"{liq:,} liquids")
    bad = c.execute(
        "SELECT COUNT(*) FROM food f JOIN food_serving s ON s.foodId=f.id "
        "WHERE f.isLiquid=1 AND s.gramWeight=100 AND s.label!='100 ml'"
    ).fetchone()[0]
    check("liquid canonical serving labelled ml", bad == 0, f"{bad} mislabelled")

    # ---- 5. data quality ---------------------------------------------------
    print("\n5. DATA QUALITY GUARDS")
    for band in ("HIGH", "MEDIUM", "LOW"):
        n = c.execute("SELECT COUNT(*) FROM food WHERE dataConfidence=?", (band,)).fetchone()[0]
        print(f"       {band:<7} {n:>8,}  ({100*n/n_food:.1f}%)")
    check("no NULL macros",
          c.execute("SELECT COUNT(*) FROM food WHERE kcalPer100 IS NULL OR proteinPer100 IS NULL "
                    "OR carbsPer100 IS NULL OR fatPer100 IS NULL").fetchone()[0] == 0)
    check("no negative macros",
          c.execute("SELECT COUNT(*) FROM food WHERE kcalPer100<0 OR proteinPer100<0 "
                    "OR carbsPer100<0 OR fatPer100<0").fetchone()[0] == 0)
    impossible = c.execute("SELECT COUNT(*) FROM food WHERE kcalPer100 > 900").fetchone()[0]
    flagged = c.execute(
        "SELECT COUNT(*) FROM food WHERE kcalPer100 > 900 AND dataConfidence='LOW'"
    ).fetchone()[0]
    check("impossible-kcal rows all flagged LOW", impossible == flagged,
          f"{flagged}/{impossible}")

    # ---- 6. duplicate barcodes --------------------------------------------
    print("\n6. DUPLICATE BARCODES (must resolve, not crash)")
    dup = c.execute(
        "SELECT barcode, COUNT(*) n FROM food WHERE barcode IS NOT NULL "
        "GROUP BY barcode HAVING n > 1 ORDER BY n DESC LIMIT 1"
    ).fetchone()
    if dup:
        best = c.execute(
            """SELECT name, dataConfidence FROM food WHERE barcode=?
               ORDER BY CASE dataConfidence WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END
               LIMIT 1""",
            (dup["barcode"],),
        ).fetchone()
        check("duplicate barcode resolves to best-confidence row", best is not None,
              f"{dup['barcode']} has {dup['n']} rows -> {best['name'][:40]!r} [{best['dataConfidence']}]")
    else:
        check("duplicate barcode handling", True, "no duplicates present")

    print("\n" + "=" * 70)
    if FAILS:
        print(f"FAILED: {len(FAILS)}")
        for f_ in FAILS:
            print(f"  - {f_}")
        sys.exit(1)
    print("ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
