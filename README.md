# PULSE

An offline-first Android fitness and nutrition tracker. Personal project.

Built around a bundled database of **326,759 foods** (313,442 with barcodes) and
**873 exercises**, so scanning, searching and logging all work with no network
and no account.

**Status:** in development. Food logging works end to end; workouts, progress and
the barcode scanner are not built yet. See [docs/](docs/) for the phase reports.

## Why it's built this way

- **Zero ongoing cost.** No paid APIs, no backend, no subscriptions. Every data
  source is either bundled or a free tier that a single user cannot exhaust.
- **Offline first.** Room is the single source of truth; the UI never waits on
  the network to render. Remote lookups are a bonus for the long tail, not a
  dependency.
- **No fake functionality.** If a screen is a placeholder it says so.

## Stack

Kotlin · Jetpack Compose · Material 3 · Room · Hilt · Coroutines/Flow · OkHttp

| Module | Purpose |
|---|---|
| `:app` | Application, navigation host |
| `:core:model` | Pure Kotlin — nutrition scaling, units, barcode normalisation |
| `:core:domain` | Pure Kotlin — TDEE, macros, 1RM, volume, pace, PR detection |
| `:core:database` | Room — 23 entities, 13 DAOs, FTS4 |
| `:core:data` | Repositories |
| `:core:network` | Open Food Facts client, source chain, asset downloader |
| `:feature:food` | Search, logging, create-food |

`:core:model` and `:core:domain` have no Android dependencies, so all the
nutrition and strength maths is unit-testable on the JVM.

## Building

Requires JDK 17 and the Android SDK (compileSdk 36). See
[docs/BUILD_SETUP.md](docs/BUILD_SETUP.md) — including several version traps
worth knowing about.

```bash
./gradlew build          # 213 tests
./gradlew :app:assembleDebug
```

## Data

The bundled databases are **not** in this repository — they are built from
upstream sources by the scripts in [tools/](tools/) and published as release
assets (67 MB compressed, downloaded on first run).

```bash
python tools/build_food_db.py --lean
python tools/build_exercise_db.py
python tools/stamp_room_identity.py
python tools/prepare_release_assets.py
```

### Attribution

The bundled data is derived from open datasets and is redistributed under the
**Open Database License (ODbL) v1.0**. Full terms and per-source attribution are
in `LICENSE-DATA.txt`, published alongside the release assets.

- **OpenNutrition** — https://www.opennutrition.app (ODbL)
- **Open Food Facts** — © Open Food Facts contributors,
  https://world.openfoodfacts.org (ODbL)
- **USDA FoodData Central** (public domain), **Canadian Nutrient File**
  (Open Government Licence – Canada), **Frida** (Denmark), **AUSNUT** (Australia)
- **free-exercise-db** — https://github.com/yuhonas/free-exercise-db (Unlicense)

## Licence

Source code: MIT (see `LICENSE`).
Bundled data: ODbL 1.0 (see `LICENSE-DATA.txt`) — a different licence, because
the upstream datasets require it.
