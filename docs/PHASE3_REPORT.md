# PULSE — Phase 3: Core Data & Application Engine

**Date:** 2026-08-12
**Status:** Complete — exit criterion met
**Verify:** `./gradlew.bat build` → **129 tests, 0 failures**, APK + 3 AARs

Phase 3's exit criterion was *"data can be written, read, and survives app
restart, proven by tests."* That is met, and the bundled databases are proven to
open through Room rather than assumed to.

---

## Completed

### Toolchain (previously blocked)
Android Studio 2026.1.3.7, Temurin JDK 17, Gradle 9.7, Android SDK 36 — SDK
installed headlessly so the whole setup is reproducible from a script. See
[BUILD_SETUP.md](BUILD_SETUP.md).

### Modules

| Module | Contents |
|---|---|
| `:core:model` | Nutrition scaling, energy-check confidence, metric-primary units, barcode normalization |
| `:core:domain` | BMR/TDEE, macro splits, water targets, 1RM, volume, pace, PR detection |
| `:core:database` | Room: 23 entities, 13 DAOs, 2 FTS4 indexes, schema export, Hilt module |
| `:core:data` | Repositories: food, diary, water — with Hilt bindings |
| `:app` | Compose host, Hilt application |

`:core:model` and `:core:domain` have **zero Android dependencies**, which is
what lets every calculation run as a plain JVM test.

### Bundled data

| Asset | Contents | Size |
|---|---|---|
| `opennutrition.db` | 326,759 foods · 313,442 barcodes · 654,278 servings | 197 MB (68 MB gzipped) |
| `exercises.db` | 873 exercises · muscles · equipment · instructions · FTS | 1.2 MB |

Both validated against the KSP-exported Room schema and stamped with the
identity hash by `tools/stamp_room_identity.py`.

### Test coverage — 129 tests

| Suite | Tests | Guards |
|---|---:|---|
| `NutritionTest` | 9 | Linear scaling, unknown-vs-zero, negative rejection |
| `EnergyCheckTest` | 6 | Confidence banding |
| `UnitConverterTest` | 5 | Metric round-trips, feet/inches |
| `BarcodeNormalizerTest` | 8 | UPC-E → UPC-A → EAN-13, check digits |
| `EnergyCalculatorTest` | 12 | Mifflin–St Jeor, safety floors |
| `MacroCalculatorTest` | 5 | Split maths, no negative carbs |
| `WaterCalculatorTest` | 4 | Targets and rounding |
| `OneRepMaxTest` | 8 | Epley/Brzycki, refusal beyond 12 reps |
| `VolumeTest` | 6 | Loaded/assisted bodyweight, zero for timed work |
| `PaceTest` | 3 | Seconds per km, no infinities |
| `PrDetectorTest` | 8 | Per-mode record types, pace inversion |
| `TrackingModeTest` | 1 | Dimension flags |
| `PulseDatabaseTest` | 19 | DAO behaviour, cascades, history immutability |
| `BundledAssetTest` | 7 | **Room opens the real bundled databases** |
| `RepositoryTest` | 23 | Logging, copy-on-write, FTS safety |
| `FtsQueryTest` | 5 | Query sanitization |

---

## Decisions

1. **Kotlin follows KSP, not the reverse.** KSP changed versioning scheme at
   2.3.0; 2.3.11 builds against Kotlin 2.3.20, so Kotlin was pinned back from
   2.4.10. Room and Hilt both depend on KSP.
2. **Robolectric for DAO and repository tests.** No device is attached and these
   invariants are too important to defer to Phase 12.
3. **Validate the bundled databases before stamping them.** Getting the identity
   hash right but the schema wrong is a cryptic runtime crash on first open;
   validating first turns it into a clear build-time message.
4. **The exercise seed keeps all 873 entries**, ranked, rather than culling to
   ~300 as Phase 2 suggested. Deleting data the user might want is worse than
   ranking it lower, and search ordering already surfaces generics first.
5. **`trackingMode` assignment is rule-based with an override table and a
   `--review` audit mode**, because no test can catch a semantically
   wrong-but-valid enum.
6. **FTS input is stripped to alphanumerics, not quoted.** Quoting is the
   instinct and is wrong — FTS4 will not prefix-expand a quoted phrase.
7. **`Clock` is injected** so time-dependent behaviour is pinned in tests.
8. **Missing bundled assets are non-fatal.** Room creates empty tables and the
   app still runs.

---

## Problems

- **Bundled asset delivery is still unresolved.** `exercises.db` (1.2 MB) can
  ship in the APK; `opennutrition.db` (197 MB) cannot. First-run download with a
  resumable, skippable flow is the plan — Phase 4 work, and the largest
  remaining unknown.
- **API 37 is listed by the SDK manager but unpublished**, so `compileSdk` stays
  36 and `core-ktx`, `lifecycle` and `hilt-navigation-compose` are all pinned one
  minor back. Revisit when 37 ships.
- **Remote food sources are stubbed.** `resolveBarcode` returns `NotFound`
  rather than consulting Open Food Facts. The result type and the offline branch
  are already in their final shape, so Phase 4 fills in the chain without
  reshaping callers.
- **`trackingMode` remains a judgment call** on a long tail of unusual
  exercises. Three systematic errors were found and fixed; individual oddities
  will surface in use and are one override-table entry each.

---

## Dependencies

| Needed | When | Blocking? |
|---|---|---|
| Physical Android device | Phase 12 (useful sooner) | Yes for barcode/camera |
| Hosting for the 197 MB food database | Phase 4 | Yes for first-run download |
| Contact email for the OFF `User-Agent` | Phase 4 | Before any live OFF traffic |
| Free API keys (USDA / FatSecret / Nutritionix) | Phase 4 | No — all optional |

---

## Next phase

**Phase 4 — Food & Nutrition Engine.** Food search UI over the existing
repository; the barcode scanner (CameraX + ML Kit) wired to `resolveBarcode`;
the remote source chain behind the local hit; first-run asset download; the
create-food flow; saved meals and recipes; then label OCR.

The engine those screens need is in place and tested.
