# PULSE — Phase 2: Product & Technical Architecture

**Date:** 2026-08-11
**Status:** Design complete — awaiting approval before Phase 3 (implementation)
**Depends on:** [PHASE1_RESEARCH.md](PHASE1_RESEARCH.md), including addenda §9 (personal-use scope) and §10 (zero-cost stack)

**Standing constraints carried in from Phase 1:**
- Personal use — no distribution, no commercial licensing exposure
- **$0 ongoing cost, no mandatory external service, fully functional offline from first launch**
- Bundled offline datasets are the primary data source; live APIs are optional long-tail bonuses
- Project lives outside OneDrive; no secrets in synced folders

---

## 1. Decisions made in this phase

Two Phase 1 positions are revised here, both deliberately.

### 1.1 Units: metric-primary storage, display-layer conversion

I flagged this twice without an answer, so I'm deciding it: **all persisted values are metric** — kilograms, metres, millilitres, grams, kilocalories, seconds. Imperial is a **display and input conversion only**, applied at the UI boundary.

Reasons: it matches every data source we bundle (all metric), it matches Health Connect's units so a future integration is a mapping rather than a migration, and it means unit preference is a display toggle rather than a data migration. Changing your mind about lbs vs kg must never rewrite the database.

Consequence: the UI layer owns a `UnitFormatter` and every input field parses to metric before it reaches a ViewModel. No metric/imperial ambiguity ever crosses into the domain layer.

### 1.2 One `food` table, not two — revising Phase 1 §6.1

Phase 1 committed to separate `cached_food` and `user_food` tables for licensing segregation. **Implementing that would create a polymorphic foreign key**: every `diary_entry`, `recipe_ingredient`, and `saved_meal_item` would need `(foodId, foodSource)` and could not use a real FK constraint. That's a structural defect in exchange for a property we can get more cheaply.

**Revised design: one `food` table with a `source` discriminator column.** It preserves every property the split was meant to buy:

| Requirement | How the single table satisfies it |
|---|---|
| Identify all externally-sourced data | `WHERE source != 'USER'` |
| Purge / refresh the cache wholesale | `DELETE FROM food WHERE source != 'USER' AND id NOT IN (SELECT foodId FROM diary_entry)` |
| Sync only user-owned rows later | `WHERE source = 'USER'` |
| Never mutate external data | **Copy-on-write** — editing an external food inserts a new `source = 'USER'` row with `derivedFromFoodId` set. Enforced in the repository, covered by a test. |

And it buys back: one FTS index instead of two, clean cross-source search ranking, and real foreign keys everywhere.

---

## 2. Architecture overview

Offline-first, unidirectional data flow. **Room is the single source of truth. The UI never awaits the network to render.**

```
┌──────────────────────────────────────────────────────────────────┐
│  UI  (Jetpack Compose + Material 3, re-themed in Phase 7)         │
│  Screens ── observe ──▶ UiState (immutable) ── emit ──▶ UiEvent   │
└───────────────────────────────┬──────────────────────────────────┘
                                │ StateFlow<UiState> / (Event) -> Unit
┌───────────────────────────────▼──────────────────────────────────┐
│  PRESENTATION  (ViewModels, Hilt-injected)                       │
│  Holds UiState · maps domain → display · applies UnitFormatter    │
└───────────────────────────────┬──────────────────────────────────┘
                                │ Flow<DomainModel> / suspend fun
┌───────────────────────────────▼──────────────────────────────────┐
│  DOMAIN  (pure Kotlin — no Android deps, JVM-unit-testable)      │
│  Models · NutritionCalculator · TdeeCalculator · OneRepMax        │
│  PrParser · LabelParser · ServingScaler · UseCases                │
└───────────────────────────────┬──────────────────────────────────┘
                                │ repository interfaces
┌───────────────────────────────▼──────────────────────────────────┐
│  DATA  (repository implementations)                              │
│  FoodRepository · DiaryRepository · WorkoutRepository ·           │
│  ProgressRepository · GoalRepository · WaterRepository            │
│         │                                    │                    │
│         ▼ (always)                           ▼ (best-effort)      │
│  ┌─────────────────┐              ┌──────────────────────────┐   │
│  │  Room (SoT)     │◀── write ────│  FoodDataSource chain    │   │
│  │  + FTS4         │              │  OFF → FatSecret → …     │   │
│  │  + bundled DBs  │              │  (all optional, free)    │   │
│  └─────────────────┘              └──────────────────────────┘   │
│         ▲                                                         │
│  ┌──────┴───────┐  ┌──────────────┐  ┌────────────────────────┐  │
│  │  DataStore   │  │  CameraX +   │  │  File storage          │  │
│  │  (prefs)     │  │  ML Kit      │  │  (photos, exports)     │  │
│  └──────────────┘  └──────────────┘  └────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

**The invariant that makes everything else work:** network results are *written into Room*, and the UI observes Room. There is no code path where a screen renders directly from a network response. Offline is therefore not a mode — it is the normal case with one fewer writer.

---

## 3. Module structure

```
:app                      Application, MainActivity, nav host, Hilt setup

:core:model               Pure Kotlin domain models + enums.        No Android deps.
:core:common              Result, dispatchers, date/time, unit conversion. No Android deps.
:core:domain              Calculators + use cases.                   No Android deps.
:core:database            Room entities, DAOs, migrations, seeding, FTS
:core:datastore           Preferences, goals cache, onboarding state
:core:network             Retrofit clients, DTOs, source chain, User-Agent
:core:designsystem        Phase 7 — tokens, components, motion
:core:testing             Fakes, fixtures, in-memory DB rules

:feature:home             Dashboard
:feature:food             Search, entry, meals, recipes, saved meals
:feature:scanner          CameraX + ML Kit barcode + label OCR
:feature:workout          Templates, active session, history, PRs, library
:feature:progress         Weight, measurements, charts, stats
:feature:profile          Goals, targets, settings, data management
:feature:onboarding       Phase 9
```

`:core:model`, `:core:common`, and `:core:domain` have **zero Android dependencies**. That is what makes the nutrition maths, TDEE formulas, 1RM estimation, serving scaling, and the label parser testable as plain JVM unit tests in Phase 11 — no emulator, no Robolectric — and reusable by a future Wear module.

---

## 4. Database schema

Room / SQLite. **Every user-owned table carries the sync-ready quartet** (§8): `id TEXT` (client-generated UUID), `createdAt`, `updatedAt` (epoch millis UTC), `isDeleted`, `syncState`.

Reference tables (`food` where `source != 'USER'`, seeded `exercise`) use their natural/source IDs and are exempt — they're re-derivable and never sync.

### 4.1 Profile & goals

```sql
profile                                   -- exactly one row, id = 'me'
  id TEXT PK, displayName TEXT?
  sex TEXT?                               -- MALE | FEMALE | UNSPECIFIED (BMR formula input only)
  birthDate INTEGER?                      -- epoch day
  heightCm REAL?
  activityLevel TEXT                      -- SEDENTARY|LIGHT|MODERATE|ACTIVE|VERY_ACTIVE
  goalType TEXT                           -- LOSE | MAINTAIN | GAIN
  rateKgPerWeek REAL                      -- signed; 0 for MAINTAIN
  goalWeightKg REAL?
  massUnit TEXT, lengthUnit TEXT, volumeUnit TEXT, energyUnit TEXT
  themeMode TEXT, weekStartsOn TEXT
  createdAt, updatedAt

goal_target                               -- versioned; never updated in place
  id TEXT PK
  effectiveFrom INTEGER                   -- epoch day; query = latest row <= date
  calorieTarget INTEGER
  proteinG INTEGER, carbsG INTEGER, fatG INTEGER
  waterMl INTEGER
  source TEXT                             -- CALCULATED | MANUAL
  createdAt, updatedAt, isDeleted, syncState
  INDEX(effectiveFrom)
```

`goal_target` is append-only and effective-dated. Changing your calorie target must not retroactively rewrite whether you hit target last Tuesday — a chart of "days on target" has to stay honest.

### 4.2 Food

```sql
food
  id TEXT PK
  source TEXT                             -- OPENNUTRITION|OFF|USDA|CNF|USER|RECIPE
  sourceId TEXT?                          -- upstream id, for refresh/dedup
  derivedFromFoodId TEXT? -> food(id)     -- copy-on-write provenance
  barcode TEXT?                            -- normalized EAN-13
  name TEXT, brand TEXT?
  -- canonical nutrition, always per 100 g (or per 100 ml when isLiquid)
  kcalPer100 REAL
  proteinPer100 REAL, carbsPer100 REAL, fatPer100 REAL
  fiberPer100 REAL?, sugarPer100 REAL?, satFatPer100 REAL?
  sodiumMgPer100 REAL?, cholesterolMgPer100 REAL?, potassiumMgPer100 REAL?
  isLiquid INTEGER, densityGPerMl REAL?
  ingredients TEXT?
  imageUrl TEXT?, localImagePath TEXT?     -- localImagePath also holds OCR label photos
  dataConfidence TEXT                      -- HIGH | MEDIUM | LOW  (see §6.3)
  recipeId TEXT? -> recipe(id)             -- set iff source = RECIPE
  createdAt, updatedAt, isDeleted, syncState
  INDEX(barcode), INDEX(source), INDEX(name)

food_fts  -- FTS4, external content = food(name, brand)

food_serving
  id TEXT PK, foodId -> food(id) ON DELETE CASCADE
  label TEXT                               -- "1 cup", "1 slice", "container", "100 g"
  gramWeight REAL                          -- grams (or ml when isLiquid)
  isDefault INTEGER
  sortOrder INTEGER
  INDEX(foodId)

food_usage                                 -- powers recent / frequent / favourite
  foodId TEXT PK -> food(id) ON DELETE CASCADE
  useCount INTEGER, lastUsedAt INTEGER, isFavorite INTEGER
  INDEX(lastUsedAt), INDEX(useCount), INDEX(isFavorite)
```

**Why per-100 g canonical:** it's how OpenNutrition, OFF, and USDA all store data, so import is lossless and scaling is one multiply. Servings become pure `label → gramWeight` mappings, which makes "1 serving", "2.5 servings", "1 cup", and "37 g" all the same code path.

### 4.3 Diary

```sql
diary_entry
  id TEXT PK
  date INTEGER                             -- epoch day, local
  mealType TEXT                            -- BREAKFAST|LUNCH|DINNER|SNACK
  foodId TEXT -> food(id)
  servingId TEXT? -> food_serving(id)
  quantity REAL                            -- number of servings
  grams REAL                               -- resolved, denormalized
  -- IMMUTABLE NUTRITION SNAPSHOT, taken at log time
  kcal REAL, protein REAL, carbs REAL, fat REAL
  fiber REAL?, sugar REAL?, satFat REAL?, sodiumMg REAL?
  sortOrder INTEGER, loggedAt INTEGER
  createdAt, updatedAt, isDeleted, syncState
  INDEX(date, mealType), INDEX(foodId)
```

**The snapshot is load-bearing.** Nutrition values are copied into the row at log time, not joined from `food` at read time. A bundled-database refresh, an OFF correction, or a user editing a food must never silently rewrite what you ate three weeks ago. It also makes daily totals a single indexed `SUM` with no joins.

```sql
water_entry
  id TEXT PK, date INTEGER, amountMl INTEGER, loggedAt INTEGER
  createdAt, updatedAt, isDeleted, syncState
  INDEX(date)

saved_meal                                 -- a BUNDLE: expands into N diary_entries
  id TEXT PK, name TEXT, sortOrder INTEGER
  createdAt, updatedAt, isDeleted, syncState

saved_meal_item
  id TEXT PK, savedMealId -> saved_meal(id) ON DELETE CASCADE
  foodId -> food(id), servingId?, quantity REAL, sortOrder INTEGER

recipe                                     -- ONE ITEM: materializes a food row
  id TEXT PK, name TEXT, servingsYield REAL
  notes TEXT?, imagePath TEXT?
  createdAt, updatedAt, isDeleted, syncState

recipe_ingredient
  id TEXT PK, recipeId -> recipe(id) ON DELETE CASCADE
  foodId -> food(id), servingId?, quantity REAL, sortOrder INTEGER
```

**Saved meal vs recipe** — the distinction that keeps logging uniform:
- A **saved meal** ("my usual breakfast") is a *set*. Logging it inserts several `diary_entry` rows you can edit individually.
- A **recipe** ("chilli, serves 6") is *one food*. Saving it writes/updates a `food` row with `source = RECIPE`, nutrition = Σ(ingredients) ÷ `servingsYield`. It then logs, searches, and scales exactly like any other food.

That means the entire logging path has one shape — `food + serving + quantity` — regardless of origin.

### 4.4 Exercise & workouts

```sql
exercise
  id TEXT PK, name TEXT
  primaryMuscle TEXT                       -- CHEST|BACK|SHOULDERS|BICEPS|TRICEPS|
                                           -- QUADS|HAMSTRINGS|GLUTES|CALVES|CORE|FULL_BODY
  secondaryMuscles TEXT                    -- JSON array
  equipment TEXT                           -- BODYWEIGHT|DUMBBELL|BARBELL|SMITH|CABLE|
                                           -- MACHINE|BAND|KETTLEBELL|OTHER
  category TEXT                            -- STRENGTH|CARDIO|MOBILITY|PLYOMETRIC|SPORT
  difficulty TEXT
  trackingMode TEXT                        -- see below — drives the entire logging UI
  instructions TEXT                        -- JSON array
  thumbnailPath TEXT?, animationPath TEXT?
  isUserCreated INTEGER
  createdAt, updatedAt, isDeleted, syncState
  INDEX(primaryMuscle), INDEX(equipment), INDEX(category)

exercise_fts  -- FTS4 on name

workout_template
  id TEXT PK, name TEXT, notes TEXT?
  lastPerformedAt INTEGER?, sortOrder INTEGER
  createdAt, updatedAt, isDeleted, syncState

template_exercise
  id TEXT PK, templateId -> workout_template(id) ON DELETE CASCADE
  exerciseId -> exercise(id)
  sortOrder INTEGER, supersetGroup INTEGER?, notes TEXT?

template_set                               -- prescribed sets: "3 × 8 @ 100 kg"
  id TEXT PK, templateExerciseId -> template_exercise(id) ON DELETE CASCADE
  setIndex INTEGER, setType TEXT
  targetReps INTEGER?, targetWeightKg REAL?
  targetDurationSec INTEGER?, targetDistanceM REAL?

workout
  id TEXT PK, name TEXT
  templateId TEXT? -> workout_template(id)
  startedAt INTEGER, finishedAt INTEGER?    -- NULL = in progress (crash-safe resume)
  notes TEXT?, totalVolumeKg REAL?
  createdAt, updatedAt, isDeleted, syncState
  INDEX(startedAt), INDEX(finishedAt)

workout_exercise
  id TEXT PK, workoutId -> workout(id) ON DELETE CASCADE
  exerciseId -> exercise(id)
  sortOrder INTEGER, supersetGroup INTEGER?, notes TEXT?

workout_set
  id TEXT PK, workoutExerciseId -> workout_exercise(id) ON DELETE CASCADE
  setIndex INTEGER
  setType TEXT                             -- NORMAL|WARMUP|DROP|FAILURE
  weightKg REAL?, reps INTEGER?
  durationSec INTEGER?, distanceM REAL?
  rpe REAL?, restSec INTEGER?
  isCompleted INTEGER, completedAt INTEGER?
  INDEX(workoutExerciseId)

personal_record
  id TEXT PK, exerciseId -> exercise(id)
  recordType TEXT                          -- MAX_WEIGHT|MAX_REPS|EST_ONE_RM|
                                           -- MAX_VOLUME_SET|MAX_DISTANCE|BEST_PACE|LONGEST_DURATION
  value REAL, secondaryValue REAL?         -- e.g. weight + reps for EST_ONE_RM
  workoutSetId TEXT? -> workout_set(id)
  achievedAt INTEGER
  createdAt, updatedAt, isDeleted, syncState
  INDEX(exerciseId, recordType)
```

**`trackingMode` is the field that makes one workout engine cover everything.** It's an enum on the exercise, and the set-logging UI switches on it:

| `trackingMode` | Inputs shown | Example |
|---|---|---|
| `WEIGHT_REPS` | weight, reps | Bench press |
| `REPS_ONLY` | reps | Pull-up |
| `WEIGHTED_BODYWEIGHT` | added weight, reps | Weighted dip |
| `ASSISTED_BODYWEIGHT` | assistance, reps | Assisted pull-up |
| `DURATION` | time | Plank |
| `DURATION_WEIGHT` | time, weight | Farmer's carry |
| `DISTANCE_DURATION` | distance, time (→ pace) | Running, cycling, swimming |

Without it, every cardio/mobility/bodyweight case becomes a special-cased screen. It must be correct in the seed data — a Phase 3 validation task.

### 4.5 Progress

```sql
body_measurement
  id TEXT PK
  date INTEGER                             -- epoch day
  type TEXT                                -- WEIGHT|BODY_FAT|WAIST|CHEST|HIPS|NECK|
                                           -- ARM_L|ARM_R|THIGH_L|THIGH_R|CALF_L|CALF_R|CUSTOM
  customLabel TEXT?
  value REAL                               -- kg for WEIGHT, cm for lengths, % for BODY_FAT
  note TEXT?, photoPath TEXT?
  loggedAt INTEGER
  createdAt, updatedAt, isDeleted, syncState
  INDEX(type, date)
```

One table with a `type` enum rather than a wide row per date — it supports arbitrary custom measurements, sparse logging (weight daily, waist monthly), and adding a new measurement type without a migration.

### 4.6 Aggregates

**No `daily_nutrition` table.** Daily totals are computed on demand:

```sql
SELECT SUM(kcal), SUM(protein), SUM(carbs), SUM(fat)
FROM diary_entry WHERE date = ? AND isDeleted = 0;
```

With `INDEX(date, mealType)` and the denormalized snapshot columns, this is a covering index scan over a handful of rows. Materializing it would add cache-invalidation bugs for no measurable gain. Revisit only if profiling in Phase 11 shows a problem — and if so, as a Room `@DatabaseView` or a triggered summary table, not hand-maintained state.

### 4.7 Relationship map

```
profile (1)
goal_target (effective-dated history)

food ──1:N── food_serving
  │  └─1:1── food_usage
  ├──0:1──── food (derivedFromFoodId, copy-on-write)
  └──0:1──── recipe (source = RECIPE)

recipe ──1:N── recipe_ingredient ──N:1── food
saved_meal ──1:N── saved_meal_item ──N:1── food

diary_entry ──N:1── food
            ──N:0:1── food_serving
water_entry (standalone, by date)

exercise ──1:N── template_exercise ──N:1── workout_template
                      └──1:N── template_set
         ──1:N── workout_exercise ──N:1── workout ──0:1── workout_template
                      └──1:N── workout_set ──0:1── personal_record
         ──1:N── personal_record

body_measurement (standalone, by type + date)
```

### 4.8 Seeding & migrations

Three bundled assets, shipped as **pre-built Room databases** (not runtime JSON parsing — a 300k-row insert on first launch is a multi-second stall):

| Asset | Contents | Approx. size | Delivery |
|---|---|---|---|
| `opennutrition.db` | ~300k foods + servings, ODbL | ~60–100 MB | Downloaded on first launch |
| `off_barcodes.db` | CA/US retail products with barcodes | ~40–80 MB | Downloaded on first launch |
| `exercises.db` | ~300 curated exercises + thumbnails | ~5–15 MB | **In APK** — app must work before any download |

Prepared once by a desktop script in `/tools`, checked into releases, not the repo. `exercises.db` ships inside the APK so a brand-new install is fully usable for workouts before any network access; the two food databases download on first run with a skip option (search still works against user-created foods and live APIs).

Migration policy: explicit `Migration` objects, never `fallbackToDestructiveMigration()` — this database holds irreplaceable history. Every migration gets a Room `MigrationTestHelper` test in Phase 11.

---

## 5. Repository layer

Each repository is an interface in `:core:domain` and an implementation in `:core:data`. **All reads return `Flow`; all writes are `suspend`.**

```kotlin
interface FoodRepository {
    fun search(query: String, filter: FoodFilter): Flow<List<FoodSummary>>
    fun recent(limit: Int): Flow<List<FoodSummary>>
    fun frequent(limit: Int): Flow<List<FoodSummary>>
    fun favorites(): Flow<List<FoodSummary>>
    fun observe(foodId: String): Flow<Food?>

    suspend fun resolveBarcode(raw: String): BarcodeResult
    suspend fun createUserFood(draft: FoodDraft): String
    suspend fun editFood(foodId: String, draft: FoodDraft): String  // copy-on-write
    suspend fun toggleFavorite(foodId: String)
}

sealed interface BarcodeResult {
    data class Found(val foodId: String, val confidence: DataConfidence) : BarcodeResult
    data class Incomplete(val foodId: String, val missing: Set<NutrientField>) : BarcodeResult
    data class NotFound(val barcode: String, val suggestedName: String?) : BarcodeResult
    data class Offline(val barcode: String) : BarcodeResult
}
```

`BarcodeResult` is exhaustive by construction — there is no "error" case that leaves the UI with nothing to do. `NotFound` carries `suggestedName` (from UPCitemdb, §10.3) so the create-food screen opens pre-filled.

Other repositories follow the same shape: `DiaryRepository`, `WaterRepository`, `WorkoutRepository`, `ExerciseRepository`, `ProgressRepository`, `GoalRepository`, `BackupRepository`.

---

## 6. Data-source architecture

### 6.1 The resolution chain

```kotlin
interface FoodDataSource {
    val id: SourceId
    val requiresNetwork: Boolean
    suspend fun byBarcode(ean13: String): RemoteFood?
    suspend fun search(query: String, limit: Int): List<RemoteFood>
}
```

Ordered chain, short-circuiting on first hit:

```
1. Local Room        (user foods → bundled OpenNutrition → bundled OFF → cached)   offline
2. Open Food Facts   no key, unlimited                                             network
3. FatSecret Basic   5,000/day        ─┐
4. Nutritionix       200/day           │  optional — enabled only if a key
5. USDA FDC          1,000/hour        │  is present in local.properties
6. UPCitemdb         100/day, no key  ─┘  (name-only, seeds the create form)
```

Every remote hit is **written into `food`** with its `source` tag before the UI sees it, so the second scan of the same product is a local read.

### 6.2 Network rules (non-negotiable)

- **Device-direct only, never proxied.** OFF's limits are per-IP (15 req/min product, 10 req/min search). A shared server IP would be banned; a personal device never approaches it. Recorded as an architectural constraint.
- **Custom `User-Agent`** on the OkHttp client: `PULSE/1.0 (contact-email)`.
- **`world.openfoodfacts.net`** (basic auth `off`/`off`) in debug builds so development never touches production data.
- Every source is behind a **timeout + circuit breaker**; a slow or dead source is skipped, not awaited.
- Missing API key ⇒ that source is silently absent from the chain. **No source is required for the app to work.**

### 6.3 Barcode normalization & confidence

Normalization runs before every lookup, and it's a genuine bug source: **UPC-E → UPC-A expansion, then UPC-A (12) → EAN-13 zero-pad.** OFF and OpenNutrition key on EAN-13. This is a pure function in `:core:domain` with an exhaustive unit-test table.

`dataConfidence` is computed on ingest:

| Level | Rule |
|---|---|
| `HIGH` | kcal + all three macros + ≥1 serving definition, **and** `4P + 4C + 9F` within 10% of stated kcal |
| `MEDIUM` | Complete but the macro/energy check is off by 10–25% |
| `LOW` | Missing any required field, or the check is off by >25% |

`LOW` foods are shown with a "check this" affordance and never logged silently. This is the mitigation for crowd-sourced data quality identified in Phase 1 §2.2.

### 6.4 Label OCR pipeline

```
CameraX frames ──▶ crop to guide rect ──▶ ML Kit TextRecognizer
                                              │
                              Text + bounding boxes (per line)
                                              ▼
   LabelParser (pure Kotlin, :core:domain — fully unit-testable)
     1. anchor      "Nutrition Facts" / "Valeur nutritive" / "Calories"
     2. serving     "Per 1 cup (250 mL)" / "Serving size 2/3 cup (55g)"
     3. row match   group lines by y-overlap of bounding boxes
     4. synonyms    bilingual table (Fat/Lipides, Sugars/Sucres, …)
     5. reject %    discard %DV tokens — the #1 mis-parse
     6. validate    4P + 4C + 9F vs kcal → per-field confidence
                                              ▼
   Multi-frame voting — modal value per field across ~5 frames
                                              ▼
   Review sheet: every field editable, low-confidence highlighted,
   crop shown alongside.  USER CONFIRMS.  ──▶ food(source = USER)
```

The parser takes `List<OcrLine>` (text + rect) and returns `ParsedLabel` — no Android types — so the whole thing is testable against fixture data in Phase 11 without a camera.

---

## 7. Navigation & state

### 7.1 Navigation

Single activity, type-safe Navigation Compose, five bottom-bar destinations:

```
Home · Food · Workout · Progress · Profile
```

**Logging never navigates away.** Every quick action opens a modal bottom sheet over the current screen: `AddFoodSheet`, `ServingSheet`, `WaterSheet`, `LogSetSheet`, `QuickAddSheet`. You never lose your place to log something — this is the structural expression of "logging must be extremely fast."

Full-screen destinations are reserved for genuinely immersive flows: the scanner, an active workout session, and onboarding.

The **active workout** is special: it survives navigation as a persistent collapsed bar (à la a music player), because `workout.finishedAt IS NULL` is a global state, not a screen.

### 7.2 State management

```kotlin
data class FoodSearchUiState(
    val query: String = "",
    val content: ContentState<List<FoodSummary>> = ContentState.Loading,
    val tab: SearchTab = SearchTab.All,
    val isOffline: Boolean = false,
    val message: UiMessage? = null,
)

sealed interface ContentState<out T> {
    data object Loading : ContentState<Nothing>
    data class Empty(val reason: EmptyReason) : ContentState<Nothing>
    data class Error(val cause: ErrorKind, val retryable: Boolean) : ContentState<Nothing>
    data class Content<T>(val data: T) : ContentState<T>
}
```

One immutable `UiState` per screen, exposed as `StateFlow`, with events flowing up as a sealed `UiEvent`. **`ContentState` makes loading / empty / error / content explicit in the type system**, so a screen physically cannot forget to design its empty state — Phase 7 gets a list of every state that needs artwork by reading the sealed hierarchy.

`ViewModel` uses `stateIn(SharingStarted.WhileSubscribed(5_000))` so rotation doesn't re-query.

---

## 8. Offline & sync strategy

### 8.1 Offline

| Capability | Offline? |
|---|---|
| Log food from bundled DB (300k+ foods, incl. barcodes) | ✅ |
| Barcode scan against bundled + cached data | ✅ |
| **Nutrition label OCR** (on-device ML Kit) | ✅ |
| Create custom food / recipe / saved meal | ✅ |
| Entire workout engine, templates, history, PRs | ✅ |
| Water, weight, measurements, charts, goals | ✅ |
| Look up an *uncached, unbundled* barcode | ❌ queued + retried |
| Search foods not in the bundled set | ❌ local results only |

Two capabilities require network, both long-tail. Everything a person does daily works in airplane mode.

Offline barcode misses go into a small `pending_lookup` table and are retried by a `WorkManager` job on connectivity regained — and the user can still create the food manually right now rather than waiting.

### 8.2 Sync-readiness (no sync code in v1)

Every user-owned table carries:

- `id TEXT` — **client-generated UUID.** Autoincrement integers make offline creation unsyncable; this is the one decision that cannot be retrofitted cheaply.
- `createdAt` / `updatedAt` — epoch millis, UTC.
- `isDeleted` — soft delete. A hard delete cannot be propagated.
- `syncState` — enum, written but unread in v1.

That's four columns and zero logic. It is the difference between "add cloud sync" later being a feature and being a rewrite.

### 8.3 Backup — what actually ships

Real multi-device sync needs a server and is out of scope. The genuine need — *don't lose my data, let me change phones* — is met free (Phase 1 §10.5):

1. **Export / import** (Phase 6). One `.pulse` file: zipped SQLite + photos + a manifest with schema version. Written anywhere via the Storage Access Framework. Zero dependencies, works forever, and doubles as the "your data is yours" guarantee.
2. **Google Drive `appDataFolder`** (Phase 6+). Hidden app-private folder in *your own* Drive. Free, no server, no account system of ours. One-tap back up / restore.
3. **Android Auto Backup** for profile, goals, and preferences only (25 MB cap, doesn't consume user quota).

---

## 9. Security & privacy

| Concern | Approach |
|---|---|
| API keys (all optional, all free tiers) | `local.properties` → `BuildConfig`. **`local.properties` is gitignored.** Project sits outside OneDrive per the standing rule. |
| Health data | Stays on-device. Nothing is transmitted except barcode strings and search terms to public food APIs. |
| Photos (label crops, progress) | App-internal storage, not the shared gallery. Excluded from Auto Backup by rule. |
| Database encryption | **Not used.** SQLCipher costs real performance and complicates debugging to protect data already sandboxed per-app on a device with full-disk encryption. Reconsider only if the app ever leaves personal use. |
| `allowBackup` | Explicitly configured with a `data_extraction_rules` allowlist — decided, not defaulted. |
| Camera | Permission requested in-context at first scan, with a rationale, and a graceful denied path (manual entry). No frames are stored unless the user saves a label photo. |
| Network | HTTPS only; cleartext disabled; certificate defaults. |
| Future auth | If cloud ever arrives: tokens in EncryptedSharedPreferences backed by the Android Keystore. Never plaintext. |

---

## 10. Testing strategy (contract for Phase 11)

The module boundaries exist to make this cheap:

| Layer | Test type | Covers |
|---|---|---|
| `:core:domain` | Plain JVM unit | Serving scaling, daily totals, TDEE/BMR, macro splits, 1RM estimation, PR detection, **barcode normalization**, **LabelParser** against fixtures, unit conversion round-trips |
| `:core:database` | Instrumented (in-memory Room) | DAO queries, FK cascades, **every migration**, copy-on-write invariant, soft-delete filtering |
| `:core:data` | Unit with fakes | Source-chain ordering, short-circuit, offline fallback, circuit breaker |
| `:feature:*` | ViewModel unit | State transitions across the full `ContentState` hierarchy |
| `:app` | Compose UI + manual | Navigation, sheets, permission flows |

Non-negotiable invariants that get an explicit test each: **nutrition scales linearly** (1 serving = 200 kcal ⇒ 2.5 = 500, and all macros in proportion); **diary snapshots never change** when the source food is edited; **external food rows are never mutated**; **data survives process death and reinstall-from-backup**.

---

## Phase 2 Report

### Completed
- Full Room schema: 21 tables, all relationships, indexes, and FK cascades specified
- Architecture diagram and layer contract, with the Room-as-single-source-of-truth invariant stated explicitly
- Module structure with a dependency-free domain core, chosen to make Phase 11 cheap
- Repository interfaces including the exhaustive `BarcodeResult` type
- Data-source chain with ordering, network rules, confidence scoring, and the OCR pipeline contract
- Navigation model (5 tabs, sheets-not-screens for logging), state-management contract with `ContentState`
- Offline capability matrix, sync-readiness columns, and the free backup plan
- Security posture and the Phase 11 testing contract

### Decisions
1. **Metric-primary storage, imperial as display conversion.** Decided rather than asked again — matches every bundled dataset and Health Connect, and keeps unit preference from ever becoming a data migration.
2. **One `food` table with a `source` discriminator, revising Phase 1 §6.1.** The two-table split would have forced a polymorphic FK across `diary_entry`, `recipe_ingredient`, and `saved_meal_item`. Copy-on-write plus the `source` column preserves every property the split was for.
3. **Nutrition per 100 g canonical**, servings as `label → gramWeight`. Lossless import, one-multiply scaling.
4. **Immutable nutrition snapshot on `diary_entry`.** History must not be rewritten by a database refresh or a food edit.
5. **Recipes materialize a `food` row; saved meals expand into multiple entries.** One logging path for everything.
6. **No `daily_nutrition` table** — computed from a covering index; revisit only on profiling evidence.
7. **`goal_target` is effective-dated and append-only**, so historical adherence stays honest.
8. **Pre-built database assets, not runtime JSON import**; `exercises.db` in the APK so the app is useful before any download.
9. **Logging is bottom sheets, never navigation.** Structural expression of the speed principle.
10. **No destructive migrations, ever.**

### Problems
- **Bundled asset delivery is unresolved.** ~100–180 MB of database can't go in the APK. First-launch download from a GitHub release is the plan, but it needs a graceful skip path and resumable download. Flagged for a Phase 3 decision, not blocking design.
- **`trackingMode` correctness in the seed data** is a manual curation task across ~300 exercises. Wrong values produce a wrong logging UI, and no test can catch a semantically wrong-but-valid enum. Budget real time for it in Phase 3.
- **OpenNutrition's field mapping is unverified** — the schema above assumes per-100 g values and EAN-13 barcodes based on its documented sources. The TSV needs inspecting before the importer is written. First Phase 3 task.
- **Room + FTS4 across two attached bundled databases** needs a spike; the alternative is merging all foods into the main database at first launch, which costs disk and startup time. Phase 3.

### Dependencies
| Needed | When | Blocking? |
|---|---|---|
| Android Studio + JDK 17 | Phase 3 | Yes |
| OpenNutrition TSV download (inspect + import script) | Phase 3 | Yes — first task |
| OFF JSONL dump (~7 GB) for the barcode subset | Phase 3 | No — can follow |
| Contact email for the OFF `User-Agent` | Phase 3 | Before any live OFF traffic |
| Physical Android device | Phase 12 (useful from Phase 3) | Yes for scanning |
| Free API keys (FatSecret / Nutritionix / USDA) | Phase 4 | No — all optional |

### Next Phase
**Phase 3 — Core Data & Application Engine.** In order: inspect the OpenNutrition TSV and write the desktop importer; build the Room schema with migrations and FTS; curate and validate the ~300-exercise seed including `trackingMode`; implement repositories and the data-source chain; wire Hilt, DataStore, and the nav skeleton. No visual design work — Phase 7 owns that. The exit criterion is that data can be written, read, and survives app restart, proven by tests.

**Awaiting approval before starting Phase 3.**
