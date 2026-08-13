# PULSE data tooling

Desktop scripts that build the bundled databases PULSE ships with. These run
once per dataset release, not at app runtime. Python 3.11+, no dependencies
beyond the standard library.

## Pipeline

```bash
# 1. Download + verify the source dataset (~63 MB zip -> ~283 MB TSV)
mkdir -p data/raw && cd data/raw
curl -O https://downloads.opennutrition.app/opennutrition-dataset-2025.1.zip
curl -O https://downloads.opennutrition.app/opennutrition-dataset-2025.1.zip.sha256
sha256sum -c opennutrition-dataset-2025.1.zip.sha256
unzip -o opennutrition-dataset-2025.1.zip -d extracted
cd ../..

# 2. Inspect structure (optional — documents the dataset)
python tools/inspect_opennutrition.py

# 3. Validate field mapping + data quality across the full file
python tools/validate_opennutrition.py

# 4. Build the bundled database
python tools/build_food_db.py --lean        # 197 MB / 68 MB gzipped  <- ships
python tools/build_food_db.py               # 330 MB, adds ingredients + allergens
python tools/build_food_db.py --limit 5000  # fast dev build

# 5. Prove it works
python tools/smoke_test_db.py
```

## Scripts

| Script | Purpose |
|---|---|
| `inspect_opennutrition.py` | Dumps columns, JSON shapes, sample rows, key frequencies |
| `validate_opennutrition.py` | Full-file field-mapping + quality validation |
| `build_food_db.py` | Builds `data/build/opennutrition.db` for Room |
| `build_exercise_db.py` | Builds `data/build/exercises.db` from free-exercise-db |
| `smoke_test_db.py` | Functional test: barcode, FTS, scaling, liquids, quality guards |
| `stamp_room_identity.py` | Validates both assets against Room's schema, then stamps them |

## Exercise seed

```bash
curl -sL -o data/raw/exercises.json   https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises.json
python tools/build_exercise_db.py            # 873 exercises, 1.2 MB
python tools/build_exercise_db.py --review   # print every trackingMode assignment
```

`trackingMode` decides which inputs the set-logging UI shows, and a
wrong-but-valid enum compiles and runs — it just makes the app wrong. Assignment
is rule-based with an explicit override table, and `--review` plus the printed
rule-firing breakdown exist so the result can actually be audited.

Three real bugs were caught that way and are now regression-guarded by the rules:

| Bug | Cause | Fix |
|---|---|---|
| `Hang Clean` / `Hang Snatch` → `DURATION_WEIGHT` | "hang" matched the carries-and-holds rule | negative lookahead for `clean\|snatch\|power\|pull\|high` |
| Every push-up variant → `WEIGHTED_BODYWEIGHT` | push-ups were in the belt-loadable list | removed — they *can* be loaded, but plain reps is the overwhelming case |
| `Dips - Triceps Version` → `REPS_ONLY` (no way to log a belt) | loadable check ran only for `equipment == "body only"`, but rings/parallel bars are tagged `other` | check moved ahead of the bodyweight branch and widened to all bodyweight-ish equipment |

## Build flags

| Flag | Effect |
|---|---|
| *(none)* | Everything, incl. ingredient text + allergen analysis — **330 MB** |
| `--lean` | Omits ingredients + allergens (61% of text) — **197 MB / 68 MB gzipped** |
| `--no-micros` | Also drops the curated micronutrient JSON |
| `--limit N` | First N foods, for fast iteration |

`--lean` is what ships. Ingredient text isn't needed by a macro tracker, and
per-product ingredients can be fetched live from Open Food Facts on demand.

## Current output

326,759 foods · 313,442 barcodes · 654,278 servings · 197.2 MB
Confidence: HIGH 81.1% · MEDIUM 11.9% · LOW 7.0%

## Known follow-up

Room verifies a schema identity hash in `room_master_table`. That hash is only
known once the Room entities compile and export `build/schemas/*.json`. Until
then Room rejects the asset with a mismatch error — expected. A
`stamp_room_identity.py` step gets added once `:core:database` compiles.

## Licensing

Data is OpenNutrition (ODbL, modified DbCL), incorporating Open Food Facts.
Attribution requirements are recorded in `docs/DATASET_FINDINGS.md` §6.
Neither the source dataset nor built databases are committed — see `.gitignore`.
