"""Validate the built food database against Room's exported schema, then stamp it.

Room verifies a prepopulated database two ways at open time:
  1. `room_master_table` must contain the identity hash of the compiled schema.
  2. The actual table/column/index structure must match the schema exactly.

Getting (1) right but (2) wrong produces a runtime crash on first open with a
long, hard-to-read diff. This script checks (2) *before* stamping (1), so a
mismatch is a clear message at build time instead.

Run (after :core:database has compiled at least once):
    python tools/stamp_room_identity.py
    python tools/stamp_room_identity.py --db data/build/opennutrition.db
"""

from __future__ import annotations

import argparse
import glob
import json
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMA_GLOB = str(ROOT / "core/database/schemas/**/*.json")

# Each bundled asset ships only a subset of the entities. The rest are created
# by Room on first open; a prepopulated DB need not contain every table.
ASSETS: dict[str, set[str]] = {
    "data/build/opennutrition.db": {"food", "food_serving"},
    "data/build/exercises.db": {"exercise"},
}

ROOM_MASTER_ID = 42


def load_schema() -> dict:
    files = sorted(glob.glob(SCHEMA_GLOB, recursive=True))
    if not files:
        sys.exit(
            "No Room schema JSON found.\n"
            "Compile the database module first:\n"
            "    ./gradlew.bat :core:database:assembleDebug"
        )
    latest = max(files, key=lambda f: int(Path(f).stem) if Path(f).stem.isdigit() else 0)
    return json.load(open(latest, encoding="utf-8"))["database"], latest


def sqlite_columns(conn: sqlite3.Connection, table: str) -> dict[str, dict]:
    return {
        r[1]: {"type": r[2].upper(), "notnull": bool(r[3]), "pk": bool(r[5])}
        for r in conn.execute(f"PRAGMA table_info({table})")
    }


def normalize_affinity(t: str) -> str:
    """SQLite type affinity — Room writes TEXT/INTEGER/REAL/BLOB."""
    t = (t or "").upper()
    if "INT" in t:
        return "INTEGER"
    if any(k in t for k in ("CHAR", "CLOB", "TEXT")):
        return "TEXT"
    if "BLOB" in t:
        return "BLOB"
    if any(k in t for k in ("REAL", "FLOA", "DOUB")):
        return "REAL"
    return "NUMERIC"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=None,
                    help="stamp one asset; default stamps every known asset")
    ap.add_argument("--force", action="store_true",
                    help="stamp even if validation reports problems")
    args = ap.parse_args()

    schema, schema_file = load_schema()

    print("=" * 72)
    print("ROOM SCHEMA VALIDATION")
    print("=" * 72)
    print(f"  schema file  : {Path(schema_file).relative_to(ROOT)}")
    print(f"  version      : {schema['version']}")
    print(f"  identityHash : {schema['identityHash']}")
    print()

    targets = (
        {str(args.db.as_posix()).replace(str(ROOT.as_posix()) + "/", ""): None}
        if args.db else ASSETS
    )

    failed = False
    for rel_path, tables in targets.items():
        db_path = ROOT / rel_path
        if tables is None:
            tables = ASSETS.get(rel_path)
            if tables is None:
                sys.exit(f"Unknown asset {rel_path}; add it to ASSETS.")
        if not db_path.exists():
            print(f"  SKIP {rel_path} — not built")
            continue
        if not stamp_one(db_path, tables, schema, args.force):
            failed = True
        print()

    if failed:
        sys.exit(1)


def stamp_one(db_path: Path, bundled_tables: set[str], schema: dict, force: bool) -> bool:
    identity_hash = schema["identityHash"]
    version = schema["version"]

    print("-" * 72)
    print(f"  database : {db_path.relative_to(ROOT)}")

    entities = {e["tableName"]: e for e in schema["entities"]}
    conn = sqlite3.connect(db_path)
    actual_tables = {
        r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
    }

    problems: list[str] = []

    for table in sorted(bundled_tables):
        if table not in entities:
            problems.append(f"{table}: not present in the Room schema")
            continue
        if table not in actual_tables:
            problems.append(f"{table}: missing from the built database")
            continue

        expected = {f["columnName"]: f for f in entities[table]["fields"]}
        actual = sqlite_columns(conn, table)

        missing = set(expected) - set(actual)
        extra = set(actual) - set(expected)
        if missing:
            problems.append(f"{table}: columns missing from DB -> {sorted(missing)}")
        if extra:
            problems.append(f"{table}: columns in DB but not in schema -> {sorted(extra)}")

        for col in sorted(set(expected) & set(actual)):
            want = normalize_affinity(expected[col]["affinity"])
            got = normalize_affinity(actual[col]["type"])
            if want != got:
                problems.append(f"{table}.{col}: type {got}, Room expects {want}")

        status = "OK" if not missing and not extra else "MISMATCH"
        print(f"  [{status}] {table:<16} {len(actual)} columns")

    # FTS tables are built by the importer, not by Room's schema check.
    for table in sorted(bundled_tables):
        fts = f"{table}_fts"
        if fts in entities:
            ok = fts in actual_tables
            print(f"  [{'OK' if ok else 'WARN'}] {fts:<16} "
                  f"{'present' if ok else 'absent — search will be empty until rebuilt'}")

    if problems:
        print(f"  {len(problems)} PROBLEM(S):")
        for p in problems:
            print(f"    - {p}")
        if not force:
            print("\n  Not stamping. Fix the importer so its schema matches, "
                  "or re-run with --force.")
            conn.close()
            return False
        print("  --force given; stamping anyway.")
    else:
        print("  Schema matches Room's expectations.")

    conn.execute(
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
    )
    conn.execute(
        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (?, ?)",
        (ROOM_MASTER_ID, identity_hash),
    )
    conn.execute(f"PRAGMA user_version = {version}")
    conn.commit()

    stamped = conn.execute(
        "SELECT identity_hash FROM room_master_table WHERE id = ?", (ROOM_MASTER_ID,)
    ).fetchone()[0]
    uv = conn.execute("PRAGMA user_version").fetchone()[0]
    conn.close()

    size_mb = db_path.stat().st_size / (1024 * 1024)
    print(f"  STAMPED  identity_hash={stamped}  user_version={uv}  ({size_mb:,.1f} MB)")
    return True


if __name__ == "__main__":
    main()
