"""Build the bundled exercise database from free-exercise-db.

Source: https://github.com/yuhonas/free-exercise-db (Unlicense / public domain
for the dataset text). 873 exercises.

The hard part is **trackingMode** — the field that decides which inputs the
set-logging UI shows. No test can catch a semantically wrong-but-valid enum
(a plank marked WEIGHT_REPS compiles and runs, it just makes the app wrong), so
assignment is rule-based with an explicit override table, and the script prints
a full breakdown for eyeball review.

Run:
    python tools/build_exercise_db.py
    python tools/build_exercise_db.py --review    # print every assignment
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
import time
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "data/raw/exercises.json"
OUT = ROOT / "data/build/exercises.db"

IMAGE_BASE = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/"

# --- tracking modes ---------------------------------------------------------
WEIGHT_REPS = "WEIGHT_REPS"
REPS_ONLY = "REPS_ONLY"
WEIGHTED_BODYWEIGHT = "WEIGHTED_BODYWEIGHT"
ASSISTED_BODYWEIGHT = "ASSISTED_BODYWEIGHT"
DURATION = "DURATION"
DURATION_WEIGHT = "DURATION_WEIGHT"
DISTANCE_DURATION = "DISTANCE_DURATION"

VALID_MODES = {
    WEIGHT_REPS, REPS_ONLY, WEIGHTED_BODYWEIGHT, ASSISTED_BODYWEIGHT,
    DURATION, DURATION_WEIGHT, DISTANCE_DURATION,
}

# --- muscles ----------------------------------------------------------------
MUSCLE_MAP = {
    "quadriceps": "QUADS",
    "hamstrings": "HAMSTRINGS",
    "glutes": "GLUTES",
    "calves": "CALVES",
    "chest": "CHEST",
    "shoulders": "SHOULDERS",
    "triceps": "TRICEPS",
    "biceps": "BICEPS",
    "forearms": "FOREARMS",
    "abdominals": "CORE",
    "lats": "BACK",
    "middle back": "BACK",
    "lower back": "BACK",
    "traps": "BACK",
    "neck": "NECK",
    "adductors": "ADDUCTORS",
    "abductors": "ABDUCTORS",
}

EQUIPMENT_MAP = {
    "barbell": "BARBELL",
    "e-z curl bar": "BARBELL",
    "dumbbell": "DUMBBELL",
    "body only": "BODYWEIGHT",
    "cable": "CABLE",
    "machine": "MACHINE",
    "kettlebells": "KETTLEBELL",
    "bands": "BAND",
    "medicine ball": "OTHER",
    "exercise ball": "OTHER",
    "foam roll": "OTHER",
    "other": "OTHER",
    None: "OTHER",
}

CATEGORY_MAP = {
    "strength": "STRENGTH",
    "powerlifting": "STRENGTH",
    "olympic weightlifting": "STRENGTH",
    "strongman": "STRENGTH",
    "stretching": "MOBILITY",
    "plyometrics": "PLYOMETRIC",
    "cardio": "CARDIO",
}

DIFFICULTY_MAP = {"beginner": "BEGINNER", "intermediate": "INTERMEDIATE", "expert": "ADVANCED"}

# --- tracking-mode rules ----------------------------------------------------

# Cardio machines/activities that genuinely record distance. The rest are
# duration-only — a Stairmaster has no meaningful distance, and reporting a
# fabricated one would corrupt pace PRs.
CARDIO_WITH_DISTANCE = re.compile(
    r"\b(run|running|jog|jogging|walk|walking|bicycl|bike|cycling|row|rowing|"
    r"elliptical|skating|swim|swimming|sprint)\b", re.I,
)

# Bodyweight movements *routinely* loaded with a belt or vest.
# Push-ups are deliberately excluded: they can be weighted, but the overwhelmingly
# common case is plain reps, and offering a weight field on every incline push-up
# variant makes the logging UI wrong for almost everyone.
LOADABLE_BODYWEIGHT = re.compile(
    r"\b(pull[- ]?ups?|pullups?|chin[- ]?ups?|chinups?|chins?|dips?|muscle[- ]?ups?)\b", re.I,
)
# Bare "chin" is safe here only because this rule is gated on bodyweight
# equipment — it would otherwise catch e.g. "Barbell Triceps Press To Chin".

# Equipment values that mean "the load is your own body". The source data is
# inconsistent here — dips on rings or parallel bars are tagged "other", not
# "body only" — so the bodyweight rules must consider all three.
BODYWEIGHT_EQUIPMENT = {"body only", "other", None}

ASSISTED = re.compile(r"\bassist(ed)?\b", re.I)

# Loaded carries and holds — weight plus time, no reps.
# "hang" needs a lookahead: a dead hang is a timed hold, but a *hang clean* or
# *hang snatch* is an explosive lift from the hang position and is rep work.
CARRY_OR_HOLD = re.compile(
    r"\b(farmer|carry|carries|suitcase|waiter|yoke|"
    r"overhead hold|wall sit|plate pinch|hand squeeze|"
    r"hang(?!\s*(clean|snatch|power|pull|high|position)))\b", re.I,
)

ISOMETRIC = re.compile(
    r"\b(plank|bridge|hold|isometric|superman|hollow|l[- ]?sit|balance)\b", re.I,
)

# Explicit overrides for cases the rules get wrong. Keyed by exact source name.
# This is the escape hatch that makes rule-based assignment safe.
OVERRIDES: dict[str, str] = {
    "Rope Jumping": DURATION,
    "Prowler Sprint": DURATION,
    "Stairmaster": DURATION,
    "Step Mill": DURATION,
    "Plate Pinch": DURATION_WEIGHT,
    "Standing Olympic Plate Hand Squeeze": DURATION_WEIGHT,
    "Plank": DURATION,
    "Side Bridge": DURATION,
    "Isometric Neck Exercise - Front And Back": DURATION,
    "Isometric Neck Exercise - Sides": DURATION,
}

# Exercises surfaced by default in the library. Everything else stays
# searchable — deleting data the user might want is worse than ranking it lower.
COMMON = re.compile(
    r"^(barbell |dumbbell |cable |machine )?"
    r"(bench press|incline bench press|decline bench press|squat|front squat|"
    r"deadlift|romanian deadlift|sumo deadlift|overhead press|military press|"
    r"bent over row|pull[- ]?up|chin[- ]?up|dip|push[- ]?up|lunge|leg press|"
    r"lat pulldown|seated cable row|leg curl|leg extension|calf raise|"
    r"bicep curl|hammer curl|tricep|lateral raise|face pull|plank|"
    r"hip thrust|shoulder press|chest fly|crunch|russian twist|"
    r"good morning|shrug|clean|snatch|thruster|burpee|mountain climber)",
    re.I,
)


def tracking_mode(ex: dict) -> tuple[str, str]:
    """Return (mode, reason). Reason is printed in --review for auditing."""
    name = ex["name"]
    category = ex.get("category")
    equipment = ex.get("equipment")
    force = ex.get("force")

    if name in OVERRIDES:
        return OVERRIDES[name], "explicit override"

    if category == "cardio":
        if CARDIO_WITH_DISTANCE.search(name):
            return DISTANCE_DURATION, "cardio with distance"
        return DURATION, "cardio, no meaningful distance"

    if ASSISTED.search(name):
        return ASSISTED_BODYWEIGHT, "assisted machine"

    if CARRY_OR_HOLD.search(name):
        # A loaded carry needs weight + time; an unloaded hold is duration only.
        if equipment in ("body only", None):
            return DURATION, "unloaded hold"
        return DURATION_WEIGHT, "loaded carry/hold"

    if category == "stretching":
        return DURATION, "stretch — held, not repped"

    if force == "static":
        return DURATION, "isometric (force=static)"

    if ISOMETRIC.search(name):
        return DURATION, "isometric by name"

    # Checked before the plain-bodyweight branch, and across all bodyweight-ish
    # equipment values: a dip is belt-loadable whether the source tagged it
    # "body only" (parallel bars) or "other" (rings). Getting the order wrong
    # sends dips to REPS_ONLY, where added weight cannot be logged at all.
    if equipment in BODYWEIGHT_EQUIPMENT and LOADABLE_BODYWEIGHT.search(name):
        return WEIGHTED_BODYWEIGHT, "bodyweight, routinely belt-loaded"

    if equipment == "body only":
        return REPS_ONLY, "bodyweight, not practically loadable"

    if category == "plyometrics" and equipment in ("body only", None, "other"):
        return REPS_ONLY, "plyometric, bodyweight"

    return WEIGHT_REPS, "default: loaded rep work"


SCHEMA = """
PRAGMA journal_mode = OFF;
CREATE TABLE IF NOT EXISTS exercise (
    id                TEXT    NOT NULL PRIMARY KEY,
    name              TEXT    NOT NULL,
    primaryMuscle     TEXT    NOT NULL,
    secondaryMuscles  TEXT,
    equipment         TEXT    NOT NULL,
    category          TEXT    NOT NULL,
    difficulty        TEXT,
    trackingMode      TEXT    NOT NULL,
    instructions      TEXT,
    thumbnailPath     TEXT,
    animationPath     TEXT,
    isUserCreated     INTEGER NOT NULL DEFAULT 0,
    createdAt         INTEGER NOT NULL,
    updatedAt         INTEGER NOT NULL,
    isDeleted         INTEGER NOT NULL DEFAULT 0,
    syncState         TEXT    NOT NULL DEFAULT 'SYNCED'
);
"""

INDEXES = """
CREATE INDEX IF NOT EXISTS index_exercise_primaryMuscle ON exercise(primaryMuscle);
CREATE INDEX IF NOT EXISTS index_exercise_equipment     ON exercise(equipment);
CREATE INDEX IF NOT EXISTS index_exercise_category      ON exercise(category);
CREATE INDEX IF NOT EXISTS index_exercise_name          ON exercise(name);
"""

FTS = """
CREATE VIRTUAL TABLE IF NOT EXISTS exercise_fts USING fts4(
    name, content=`exercise`, tokenize=unicode61
);
INSERT INTO exercise_fts(docid, name) SELECT rowid, name FROM exercise;
INSERT INTO exercise_fts(exercise_fts) VALUES('optimize');
"""

COLS = [
    "id", "name", "primaryMuscle", "secondaryMuscles", "equipment", "category",
    "difficulty", "trackingMode", "instructions", "thumbnailPath",
    "animationPath", "isUserCreated", "createdAt", "updatedAt", "isDeleted",
    "syncState",
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--review", action="store_true", help="print every assignment")
    args = ap.parse_args()

    if not SRC.exists():
        sys.exit(f"Missing source: {SRC}")

    data = json.load(open(SRC, encoding="utf-8"))
    now = int(time.time() * 1000)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    if OUT.exists():
        OUT.unlink()
    conn = sqlite3.connect(OUT)
    conn.executescript(SCHEMA)

    rows = []
    modes = Counter()
    reasons = Counter()
    muscles = Counter()
    skipped = []
    review_lines = []
    common = 0

    for ex in data:
        prim = (ex.get("primaryMuscles") or [None])[0]
        muscle = MUSCLE_MAP.get(prim)
        if muscle is None:
            skipped.append((ex["name"], f"unmapped muscle {prim!r}"))
            continue

        mode, reason = tracking_mode(ex)
        assert mode in VALID_MODES, f"{ex['name']}: invalid mode {mode}"
        modes[mode] += 1
        reasons[f"{mode} <- {reason}"] += 1
        muscles[muscle] += 1

        secondary = [
            MUSCLE_MAP[m] for m in (ex.get("secondaryMuscles") or []) if m in MUSCLE_MAP
        ]
        images = ex.get("images") or []
        thumb = IMAGE_BASE + images[0] if images else None
        anim = IMAGE_BASE + images[1] if len(images) > 1 else None

        if COMMON.search(ex["name"]):
            common += 1

        rows.append((
            ex["id"],
            ex["name"],
            muscle,
            json.dumps(sorted(set(secondary)), separators=(",", ":")) if secondary else None,
            EQUIPMENT_MAP.get(ex.get("equipment"), "OTHER"),
            CATEGORY_MAP.get(ex.get("category"), "STRENGTH"),
            DIFFICULTY_MAP.get(ex.get("level")),
            mode,
            json.dumps(ex.get("instructions") or [], separators=(",", ":"), ensure_ascii=False),
            thumb,
            anim,
            0, now, now, 0, "SYNCED",
        ))
        review_lines.append(f"  {mode:<22} {ex['name'][:56]:<58} ({reason})")

    conn.executemany(
        f"INSERT OR REPLACE INTO exercise ({','.join(COLS)}) "
        f"VALUES ({','.join('?' * len(COLS))})",
        rows,
    )
    conn.executescript(INDEXES)
    conn.executescript(FTS)
    conn.commit()
    conn.execute("VACUUM")
    conn.close()

    if args.review:
        print("ASSIGNMENTS")
        print("=" * 90)
        for line in sorted(review_lines):
            print(line)
        print()

    print("=" * 66)
    print(f"BUILT  {OUT.relative_to(ROOT)}")
    print("=" * 66)
    print(f"  source exercises      {len(data):,}")
    print(f"  written               {len(rows):,}")
    print(f"  skipped               {len(skipped):,}")
    print(f"  matched 'common' list {common:,}")
    print(f"  file size             {OUT.stat().st_size / 1024:,.0f} KB")

    print("\n  TRACKING MODES")
    for m, n in modes.most_common():
        print(f"    {m:<24} {n:>4}")

    print("\n  MUSCLES")
    for m, n in muscles.most_common():
        print(f"    {m:<24} {n:>4}")

    print("\n  RULE FIRINGS (audit this — a wrong mode makes the logging UI wrong)")
    for r, n in reasons.most_common():
        print(f"    {n:>4}  {r}")

    if skipped:
        print("\n  SKIPPED")
        for name, why in skipped[:20]:
            print(f"    {name[:50]:<52} {why}")


if __name__ == "__main__":
    main()
