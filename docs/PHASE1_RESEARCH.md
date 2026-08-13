# PULSE — Phase 1: Data, API & External Service Research

**Date:** 2026-08-11
**Status:** Research complete — **superseded in part by the Addendum (§9) below.** Read §9 alongside §1–§8.
**Scope:** Personal use, not for sale or distribution. See §9.1 — this changes several conclusions in §2, §4, §6, and §7.
**Repository state:** Greenfield. `C:\Users\damia\Desktop\Fitness Tracker` is empty. The other working directory (`tennis-prediction-engine`) is an unrelated project and contributes nothing to this build. There is no existing stack to inherit or preserve.

---

## 1. Recommended Stack (summary)

| Layer | Recommendation |
|---|---|
| Language | Kotlin 2.x |
| UI framework | Jetpack Compose + Material 3 (heavily re-themed — see Phase 7) |
| Architecture | MVVM + repository layer, unidirectional data flow, `StateFlow` |
| DI | Hilt |
| Local database | Room (SQLite) + FTS4 virtual tables for food/exercise search |
| Key–value settings | DataStore (Proto or Preferences) |
| Networking | Retrofit + OkHttp + kotlinx.serialization |
| Barcode scanning | **CameraX + ML Kit Barcode Scanning** (Play-Services-unbundled model) |
| Primary barcode/branded food data | **Open Food Facts** (ODbL) |
| Generic/whole food data | **USDA FoodData Central** (CC0) + **Canadian Nutrient File** (Open Government Licence – Canada) |
| Fallback food data | User-created custom foods (local, owned by user) |
| Exercise data | **Our own curated seed database**, text bootstrapped from `free-exercise-db` (Unlicense). No third-party images. |
| Images (food) | Remote OFF image URLs via Coil, disk-cached. No bulk image redistribution. |
| Charts | **Vico** (Compose-native) |
| Auth | **None in v1** — local-only, no account |
| Cloud/sync | **Deferred.** Schema designed sync-ready from day one (see §4.4) |
| Crash reporting | Firebase Crashlytics (add in Phase 12, opt-in) |
| Analytics | **None in v1** |
| Health integration | Health Connect — deferred, but units/data types chosen to match it |
| Min SDK | 26 (ML Kit needs 23; 26 gives `java.time` without desugaring headaches) |

**Headline decision: v1 ships with zero paid API dependencies and zero recurring cost.** Every commercial nutrition API evaluated (Nutritionix, Edamam, FatSecret Premier, Spoonacular) is either priced beyond a pre-revenue app or imposes white-label/attribution terms that constrain the product. The open-data combination below covers our needs — including Canada — better than any single paid vendor at our stage.

---

## 2. Food & Nutrition API Comparison

| Service | Free tier | Commercial use | Barcode | Canada coverage | Nutrition depth | Key limitations |
|---|---|---|---|---|---|---|
| **Open Food Facts** | Unlimited, free. 15 req/min/IP product reads, 10 req/min/IP search | ✅ Yes — ODbL 1.0 (data), DbCL (contents), CC-BY-SA 3.0 (images) | ✅ Excellent — barcode *is* the primary key; 3.7M+ products globally | ✅ **~124,000 products** on the Canadian instance | Full: kcal, P/C/F, fibre, sugar, sodium, sat fat, ingredients, serving size, images | Crowd-sourced → variable quality, missing fields common. Share-alike obligations. Rate limits are per-IP. |
| **USDA FoodData Central** | Free, 1,000 req/hr/IP, free data.gov key | ✅ Yes — **CC0 / public domain**, no attribution required | ⚠️ Branded foods carry GTIN/UPC but coverage is US-centric and stale | ❌ US products only | Best-in-class for generic/whole foods (Foundation, SR Legacy, FNDDS); 600k+ items | Branded set is US-only and updated monthly. No Canadian retail products. |
| **Canadian Nutrient File (Health Canada)** | Free, open data + JSON/XML API | ✅ Yes — Open Government Licence – Canada (attribution only) | ❌ No barcodes | ✅ **Authoritative for Canadian generic foods** | Very deep nutrient profiles for foods commonly eaten in Canada | Generic foods only — no brands, no barcodes. Small dataset (~5,700 foods). API is clunky. |
| **Nutritionix** | 200 calls/**day**, attribution required, no non-commercial trial | 💰 Commercial licensing negotiated per app; reported entry ~$1,850/mo (600k+ UPCs) | ✅ Strong, incl. restaurant menus | ⚠️ US-weighted | Very high quality, curated | Daily cap (not monthly pool) makes free tier unusable in production. Cliff from free → enterprise quote. |
| **FatSecret Platform** | Basic: 5,000 calls/day. "Premier Free" for startups/non-profits | ⚠️ Free/Premier-Free tiers **require attribution**; white-label + non-US datasets require paid Premier (quote-only) | ✅ Claims >90% global barcode coverage | ✅ Good — genuinely international incl. Canada | High quality, curated | Pricing opaque. Non-US data behind paid tier. Attribution requirement on free tiers conflicts with a clean branded UX. |
| **Edamam Food Database** | Free: 1,000 req/day, 50 req/min | 💰 Paid tiers to ~$799/mo for Food & Grocery DB | ⚠️ UPC lookup gated to higher tiers | ⚠️ US/EU-weighted | Good; strong NLP text parsing | Free tier explicitly non-commercial in practice; caching/persistence rights restricted. |
| **Spoonacular** | Small free quota (points-based) | 💰 ~$29–$149+/mo | ❌ Weak | ❌ Weak | Recipe-oriented, not a food-logging DB | Wrong tool — it is a recipe/meal-planning API, not a nutrition-logging backbone. |

### 2.1 Why the open-data trio wins

1. **Barcode-first logging is the killer feature, and Open Food Facts is barcode-native.** A paid vendor gives us cleaner data but the same product could be missing from either. OFF additionally lets the *user* fix or add a product, which converts a dead-end scan into a contribution.
2. **Canadian coverage is the specific reason to avoid USDA-only and Nutritionix-only designs.** OFF's Canadian instance carries ~124k products; USDA carries none. CNF fills the generic-food gap authoritatively (e.g. "chicken breast, roasted") with Canadian measurement conventions.
3. **Permanent local caching is explicitly allowed** by ODbL, CC0, and OGL-Canada. Edamam and Nutritionix restrict persistence — a hard blocker for an offline-first app.
4. **Cost of $0 with no cliff.** Nutritionix's free tier (200 calls/day *total*, not per user) would be exhausted by roughly 10 users on day one.

### 2.2 Data-quality mitigation

OFF's weakness is completeness, not correctness. Mitigations designed into Phase 3/4:

- Compute a **confidence score** per cached product (does it have kcal + all three macros + a serving size?). Products below threshold are shown with a "verify this" affordance rather than silently logged.
- Sanity validation: reject/flag records where `4·protein + 4·carbs + 9·fat` deviates from stated kcal by more than ~25%.
- Always allow the user to override any field; the override is stored as a **user-owned food** that shadows the cached one.
- Prefer CNF/USDA rows for generic foods in search ranking; prefer OFF for anything with a barcode.

---

## 3. Barcode Scanning

### 3.1 Technology choice

**CameraX + ML Kit Barcode Scanning (unbundled model via Google Play services).**

| Option | Verdict |
|---|---|
| **ML Kit** | ✅ **Chosen.** Free, no per-scan cost, on-device (works offline, no image leaves the phone). ML-based decoding is materially more robust than ZXing on rotation, glare, low light, motion, and curved/crumpled packaging — exactly the conditions of scanning a cereal box in a kitchen. Unbundled model adds only ~200 KB (bundled ~2.4 MB). |
| ZXing / ZXing-Android-Embedded | ❌ In maintenance mode; heuristic decoder; noticeably worse on damaged or angled codes. Only advantage is no Play-Services dependency. |
| Google **code scanner** (`GmsBarcodeScanning`) | ⚠️ Tempting — permission-less and zero UI work — but it renders **Google's** scanning UI. Phase 7 requires a distinctive branded scanner. Rejected for the primary flow; may be kept as a fallback for devices where camera permission is denied. |
| Commercial SDKs (Scanbot, Scandit, barKoder) | ❌ Licensed per-app/per-scan, thousands per year. Unjustifiable — ML Kit is sufficient for 1D retail codes. |

**Formats enabled:** `EAN_13`, `EAN_8`, `UPC_A`, `UPC_E`, `ITF`, `CODE_128`. Restricting the format set measurably speeds up detection. QR is deliberately *excluded* from the food scanner — it is not a retail product code and only creates false positives.

**Normalization requirement:** UPC-E must be expanded to UPC-A, and UPC-A (12 digits) must be zero-padded to EAN-13 (13 digits) before lookup. Open Food Facts keys on the EAN-13 form. This is a real source of "product not found" bugs and must be a tested utility function in Phase 3.

### 3.2 Scan resolution flow

```
SCAN (ML Kit, on-device)
  │
  ├─ normalize to EAN-13
  │
  ├─ 1. Local Room lookup (user foods → cached OFF products)
  │      HIT  → serving sheet → log.  ~instant, works offline.
  │
  ├─ 2. Remote Open Food Facts lookup (only if online)
  │      HIT  → cache locally (tagged source=OFF) → serving sheet → log
  │
  ├─ 3. Not found anywhere
  │      → "We don't know this product yet"
  │      → "Create it" — prefilled with the scanned barcode
  │      → user enters name, brand, serving size, kcal, P/C/F (+ optional
  │        fibre, sugar, sodium, sat fat)
  │      → saved locally as a user-owned food keyed by that barcode
  │      → next scan of that barcode resolves instantly from step 1
  │      → optional, explicit, opt-in: offer to contribute it to OFF
  │
  ├─ 4. Found but incomplete (missing kcal or macros)
  │      → show what we have, highlight missing fields as editable
  │      → user fills the gaps → stored as a local user override
  │
  └─ 5. Offline and not cached
         → "You're offline — we'll look this up when you reconnect"
         → queue the barcode; still allow "create manually" immediately
         → background retry on connectivity regained
```

Every branch ends in the user being able to log something. **No branch is a dead end** — that is the design rule.

### 3.3 Rate-limit architecture note (important)

Open Food Facts limits are **15 product reads/min and 10 searches/min per IP address**. Because the app calls OFF **directly from the device**, each user gets their own budget, which no realistic human scanning rate approaches.

**This forbids a naive server-side proxy.** If we ever route OFF traffic through our own backend, every user in the world shares one IP budget and we get banned. If we later need server-side food data, the correct approach is to self-host a mirror from the OFF data dump (~7 GB gzipped JSONL, ~43 GB expanded) — not to proxy.

OFF also requires a custom `User-Agent` of the form `PULSE/1.0 (contact@email)`. We must set this on the OkHttp client, and use `world.openfoodfacts.net` (basic auth `off`/`off`) for development so we never pollute production data.

---

## 4. Exercise Data

### 4.1 Recommendation: build our own, bootstrapped from public-domain text

**Do not depend on an exercise API.** Exercise data is small (hundreds of rows), essentially static, and must be available offline instantly. Shipping it as a seeded Room database is strictly better than any network call.

| Source | License | Verdict |
|---|---|---|
| **`yuhonas/free-exercise-db`** (~800 exercises, JSON) | **Unlicense (public domain)** for the dataset | ✅ Use the **JSON text** as a bootstrap for names, muscle groups, equipment, instructions, difficulty. |
| Same project's **images** | ⚠️ **Unclear.** A GitHub issue asking directly whether the images are safe for commercial use was closed without an answer. Provenance appears to be a third-party app. | ❌ **Do not ship these images.** This is a genuine legal risk, not a theoretical one. |
| **wger** exercise catalog | CC-BY-SA 4.0 (app is AGPL-3.0) | ⚠️ Usable with attribution, but the share-alike obligation on derivative *datasets* is a needless complication when a public-domain equivalent exists. Use only as a cross-reference for gaps. |
| ExerciseDB / RapidAPI wrappers | Paid, unclear underlying rights | ❌ Reject. |

### 4.2 Our exercise schema

Each exercise row carries: `name`, `primaryMuscle`, `secondaryMuscles[]`, `equipment`, `category` (strength / cardio / stretching / plyometric / etc.), `difficulty`, `instructions[]`, and critically **`trackingMode`** — an enum that drives the entire logging UI:

- `WEIGHT_REPS` — bench press
- `REPS_ONLY` — pull-ups
- `WEIGHTED_BODYWEIGHT` — weighted dips (bodyweight + added)
- `ASSISTED_BODYWEIGHT` — assisted pull-up (bodyweight − assistance)
- `DURATION` — plank
- `DURATION_WEIGHT` — farmer's carry
- `DISTANCE_DURATION` — running, cycling, swimming

This one field is what lets a single workout engine handle strength, cardio, and mobility without special-casing screens. It must be correct in the seed data.

### 4.3 Illustrations

Deferred to Phase 7. Plan: an **SVG muscle-map** rendered per exercise from the `primaryMuscle`/`secondaryMuscles` fields — originally drawn by us, one asset reused across all 300+ exercises, no per-exercise artwork, no licensing exposure, and it looks more premium than a grid of stock photos. Per-exercise animated demos are a post-launch consideration requiring a commissioned or licensed asset pack.

### 4.4 Curation over volume

Ship **~300 well-curated exercises**, not 800 noisy ones. A library full of near-duplicates ("Barbell Bench Press" / "Bench Press, Barbell" / "Flat Barbell Bench") makes search *worse*. Users add their own for anything missing.

---

## 5. Other Services — Recommendations

| Service | Recommendation | Reasoning |
|---|---|---|
| **Authentication** | ❌ Not in v1 | An account wall before first use is the single biggest onboarding drop-off in this category. The app is fully functional local-only. Add auth *when* cloud sync ships, as an optional upgrade. |
| **Cloud database / sync** | ❌ Not in v1, **but design for it now** | Retrofitting sync is expensive; designing for it is nearly free. See §6.4. When we do it: **Supabase** (Postgres, generous free tier, row-level security, straightforward Kotlin client) over Firebase, because relational data with foreign keys is a poor fit for Firestore and the export path matters. |
| **Crash reporting** | ✅ Firebase Crashlytics, added Phase 12 | Free, and shipping a health app without crash visibility is negligent. Must be disclosed in the privacy policy. |
| **Analytics** | ❌ Not in v1 | We have no analysis capacity yet, and a health app collecting behavioural telemetry raises Play Store data-safety obligations for no near-term benefit. |
| **Image storage** | ✅ Coil + OkHttp disk cache; user photos to app-internal storage | No cloud bucket needed until sync exists. |
| **Push notifications** | ❌ Not in v1 | Reminders (log dinner, drink water, rest-timer done) should be **local** notifications via WorkManager/AlarmManager. No server required, works offline, no FCM dependency. |
| **AI features** | ❌ Not in v1 | Food photo recognition and meal suggestions are genuinely valuable later, but they need a real dataset and a cost model. The Claude API is the natural fit when we get there. Architecture must not preclude it. |
| **Health Connect** | ⚠️ Deferred to post-v1, **constrain now** | Store weight in kg, distance in metres, energy in kcal, duration in seconds internally, so the eventual Health Connect adapter is a mapping layer rather than a migration. Note that publishing a Health-Connect-integrated app requires a per-data-type justification review on Play. |
| **Google Play Services** | ✅ Required (ML Kit unbundled model) | Acceptable — the target market is Play-distributed Android. |
| **Wear OS / smartwatch** | ❌ Not in v1 | Keep the domain/data modules free of Android UI dependencies so a Wear module can reuse them. |

---

## 6. Recommended Architecture

### 6.1 Data ownership model

This is the most important architectural decision in Phase 1, because it determines our licensing exposure.

| Origin | Examples | Storage | Licensing posture |
|---|---|---|---|
| **External — Open Food Facts** | Branded/barcoded products | `cached_food` table, **`source = OFF`**, with `fetchedAt` | ODbL. Attributed in-app. Segregated so it can be identified, exported, or purged. |
| **External — USDA FDC** | Generic foods, US | `cached_food`, `source = USDA` | CC0. No obligations. |
| **External — CNF** | Generic foods, Canada | Seeded into `cached_food`, `source = CNF` | OGL-Canada. Attribution in credits. |
| **Ours** | Exercise library, muscle maps, unit conversions, TDEE formulas, brand assets | Seeded Room tables + app resources | Wholly owned. |
| **User's** | Custom foods, recipes, saved meals, every log entry, weights, measurements, goals, templates, workout history | `user_*` tables | **The user's data, unambiguously.** Never mixed into cached tables. Fully exportable. |
| **Future cloud** | A sync mirror of the *user's* tables only | Supabase | We would sync only user-owned rows. Cached OFF data is re-fetchable and must **not** be uploaded — that would turn us into a redistributor. |

**The critical rule: cached external food data and user-owned food data live in separate tables, always.** A user's custom edit to an OFF product becomes a *user-owned override row* pointing at the cached row; it never mutates it. This gives us:

- Clean ODbL compliance (we can always identify and isolate OFF-derived data)
- Safe cache invalidation (we can wipe and re-fetch cached data without touching user data)
- Correct sync semantics later (only user rows sync)
- A truthful "export my data" feature

### 6.2 ODbL share-alike — the specific obligation

ODbL's share-alike clause triggers on **publicly using a derived database**. Our position:

- Caching OFF records on a user's own device for that user's own use is ordinary use, not public redistribution.
- We attribute Open Food Facts visibly (a credits screen plus attribution on any screen displaying OFF-sourced product data), with a link.
- We tag every OFF-derived row, so if we ever do publish a derived database we can produce it under ODbL without untangling it from proprietary data.
- We do **not** bake the OFF bulk dump into the APK for v1. On-demand fetch + per-user cache keeps the compliance question simple, and a 7 GB dump is not shippable anyway.
- Product images are referenced by URL, not redistributed, avoiding the CC-BY-SA image obligations *and* the third-party trademark/packaging-design rights that OFF explicitly disclaims.

If we later want offline barcode coverage without a network call, the correct move is a **curated subset** (e.g. top 50k Canadian products, ~30–60 MB) delivered as a downloadable asset pack with explicit ODbL notice — a Phase 2+ decision, flagged not decided.

### 6.3 Module / layer structure

```
:app                      navigation host, DI wiring, theme
:core:designsystem        Phase 7 — components, tokens, motion
:core:database            Room entities, DAOs, migrations, seeding
:core:datastore           preferences, goals, units
:core:network             Retrofit clients, DTOs, User-Agent, error mapping
:core:model               pure Kotlin domain models (no Android deps)
:core:common              units, date math, Result wrapper, dispatchers

:feature:food             search, entry, meals, recipes, saved meals
:feature:scanner          CameraX + ML Kit
:feature:workout          templates, active session, history, PRs
:feature:progress         weight, measurements, charts
:feature:profile          goals, targets, settings, data export
:feature:onboarding       Phase 9
:feature:home             dashboard
```

`:core:model` and the calculation logic stay free of Android dependencies so they are unit-testable on the JVM (Phase 11) and reusable by a future Wear module.

**Repository pattern, offline-first:** every repository exposes a `Flow` backed by Room as the single source of truth. Network results are written to Room; the UI observes Room. The UI never awaits a network call to render — this is what makes the app feel instant and what makes offline mode a non-feature rather than a special case.

### 6.4 Sync-ready schema (costs almost nothing now, saves a migration later)

Every user-owned table gets:

- `id: String` — **UUID generated client-side**, not an autoincrement Int (server-assigned IDs make offline creation impossible)
- `createdAt`, `updatedAt` — epoch millis, UTC
- `isDeleted: Boolean` — soft delete (a hard delete cannot be synced)
- `syncState` — enum, unused in v1, present in the schema

No sync code in v1. Just the columns. This is the difference between "add cloud sync" being a feature and being a rewrite.

### 6.5 Navigation & state

- Type-safe Navigation Compose, single-activity, bottom bar: **Home / Food / Workout / Progress / Profile**
- Logging entry points are modal bottom sheets over the current screen, not new destinations — you never lose your place to log something, which directly serves the "logging must be fast" principle
- ViewModel exposes a single immutable `UiState` data class via `StateFlow`; events flow up as sealed-class actions
- Loading / empty / error / content are explicit states in every `UiState`, designed in Phase 7 — not afterthoughts

### 6.6 Security

- No secrets in v1 worth protecting: the USDA data.gov key is free, rate-limited, and per-IP-scoped. It goes in `local.properties` → BuildConfig, **not** in version control, with `local.properties` gitignored.
- Health data stays on-device. `android:allowBackup` and cloud-backup behaviour to be decided explicitly rather than defaulted.
- When auth arrives: tokens in EncryptedSharedPreferences / DataStore backed by the Android Keystore. Never plaintext.
- Room database encryption (SQLCipher) is **not** recommended for v1 — it costs performance and complicates debugging for data that is already sandboxed per-app.

---

## 7. Risk Analysis

| Risk | Severity | Assessment & mitigation |
|---|---|---|
| **OFF data quality gaps** | 🔴 High | This is the top product risk. A user scans their Canadian yogurt and gets no calories. **Mitigation:** confidence scoring, macro/kcal sanity checks, always-editable fields, and a fast "create this food" path so a miss costs ~20 seconds, not a lost user. Cross-fill generic foods from CNF/USDA. |
| **`free-exercise-db` image provenance** | 🔴 High | Licensing question was asked publicly and never answered. **Mitigation: do not ship those images.** Use the Unlicensed JSON text only, and our own muscle-map illustrations. Non-negotiable. |
| **ODbL share-alike misread** | 🟡 Medium | Manageable but must not be improvised. **Mitigation:** source-tagged segregation (§6.1), visible attribution, no bulk redistribution in v1. Revisit formally before any offline dump or cloud upload of cached data. |
| **OFF rate limits / server-side proxy trap** | 🟡 Medium | 15 req/min per IP is generous per-device and fatal if shared. **Mitigation:** device-direct calls only; documented as an architectural constraint; local cache checked first; debounced search. |
| **OFF is a nonprofit — availability risk** | 🟡 Medium | No SLA. **Mitigation:** offline-first design means an OFF outage degrades to "can't look up new barcodes" rather than "app is broken." Everything already cached still works. Retry with backoff, clear messaging. |
| **No Canadian branded-food guarantee** | 🟡 Medium | ~124k Canadian products is good, not complete. Regional/store brands will be missing. **Mitigation:** the create-food flow is a first-class feature, not an error path. Consider FatSecret as a paid *supplementary* lookup post-revenue. |
| **Cost escalation** | 🟢 Low | v1 recurring cost is $0. Crashlytics is free. Risk only materializes if we later add a paid API or backend — a deliberate, revenue-gated decision. |
| **Play Services dependency (ML Kit)** | 🟢 Low | Excludes Huawei/AOSP-only devices. Acceptable for a Play-Store product. ZXing remains a possible fallback if that ever changes. |
| **Offline limitations** | 🟢 Low | Only two things genuinely require network: looking up an *uncached* barcode, and searching foods not yet cached. Everything else — logging, workouts, water, weight, templates, history, charts, goals — is fully offline. Recent/favourite/frequent foods are cached by definition, which covers the overwhelming majority of daily logging. |
| **Health Connect Play review** | 🟢 Low (deferred) | Requires per-data-type justification at publish time. Not a v1 concern, but do not add Health Connect casually late in the cycle. |

---

## 8. Final Recommendation

Build PULSE as an **offline-first, local-only, zero-recurring-cost Android application** on Kotlin + Compose + Room, with:

1. **Open Food Facts** as the barcode and branded-food backbone — the only option with real Canadian retail coverage, permanent caching rights, and no cost. Called device-direct, cached locally, source-tagged, attributed.
2. **USDA FoodData Central (CC0)** and the **Canadian Nutrient File (OGL-Canada)** for generic whole foods, giving accurate, authoritative data for "chicken breast" and "2% milk" where crowd-sourced data is weakest — and giving Canadian users Canadian reference values.
3. **User-created foods as a first-class feature**, not a fallback. This is what converts every data gap into a 20-second fix and permanently improves that user's experience.
4. **CameraX + ML Kit** for scanning: free, offline, on-device, robust, and fully brandable — with correct UPC-E→UPC-A→EAN-13 normalization.
5. **Our own curated ~300-exercise database**, text-bootstrapped from public-domain sources, with a `trackingMode` field that lets one engine handle strength, cardio, and mobility. No third-party images.
6. **No accounts, no cloud, no analytics in v1** — but a schema (UUIDs, timestamps, soft deletes) and a module structure that make sync, Wear OS, Health Connect, and AI features additive rather than a rewrite.

The alternative — paying Nutritionix or FatSecret — buys cleaner data at $0 today only if we accept an unusable free tier, and at a reported ~$1,850/month otherwise, with weaker Canadian coverage than OFF in the barcode case. That is the wrong trade for a pre-revenue product. It remains available to us later as a *supplement* if data-gap telemetry ever justifies it, and our source-tagged cache design means adding a second provider is a repository change, not an architectural one.

---

## Phase 1 Report

### Completed
- Inspected both working directories — confirmed greenfield, no existing stack to preserve.
- Evaluated 7 food/nutrition data sources on pricing, free tier, commercial terms, licensing, caching rights, barcode coverage, Canadian coverage, rate limits, and data quality.
- Verified licensing terms directly from primary sources (OFF terms of use, OFF API docs, ML Kit docs, `free-exercise-db` issue tracker) rather than secondary summaries.
- Confirmed the specific Canadian product count on the OFF Canadian instance (~124,407).
- Compared ML Kit / ZXing / Google code scanner / commercial SDKs for Android barcode scanning.
- Evaluated exercise-data sources and identified a concrete licensing hazard in the most commonly recommended dataset.
- Defined the barcode resolution flow across all five failure modes including offline.
- Defined data ownership, module structure, offline strategy, sync-readiness, and security posture.

### Decisions
1. **Open Food Facts is the primary food/barcode source**, supplemented by USDA FDC and the Canadian Nutrient File. No paid API in v1.
2. **OFF must be called device-direct, never through our own backend proxy** — the per-IP rate limit makes proxying a ban risk. Recorded as a hard architectural constraint.
3. **Cached external food data and user-owned food data are kept in separate tables**, with every external row tagged by source. This is the foundation of ODbL compliance and of future sync correctness.
4. **`free-exercise-db` JSON text yes, its images no.** Image licensing was queried publicly and never answered.
5. **CameraX + ML Kit** over Google's prebuilt code scanner, to allow a branded scanning UI in Phase 7.
6. **No authentication and no cloud in v1**, but UUID keys, UTC timestamps, and soft deletes from day one.
7. **`trackingMode` enum on every exercise** so one workout engine covers strength, cardio, and mobility.
8. Internal units fixed to kg / metres / kcal / seconds for future Health Connect compatibility.

### Problems
- **OFF data completeness is the principal product risk** for Canadian regional and store-brand products. Mitigated by design (fast create-food path), not eliminated. Worth measuring once real usage exists.
- **`free-exercise-db` image provenance is unresolved and should be treated as unusable.** This means original illustration work is required in Phase 7 — real scope, flagged early rather than discovered late.
- **Shipping offline barcode coverage** (a bundled OFF subset) is attractive but has unresolved ODbL redistribution implications and meaningful APK-size cost. Deliberately deferred, not decided.

### Dependencies
| Needed | When | Blocking? |
|---|---|---|
| Free data.gov API key (USDA FoodData Central) | Phase 3 | No — free, instant self-serve |
| Contact email for the OFF `User-Agent` header | Phase 3 | No — but required before any production OFF traffic |
| Android Studio + JDK 17 + an Android device or emulator | Phase 3 | Yes, for building |
| Physical Android device | Phase 12 | Yes — barcode scanning cannot be meaningfully validated on an emulator |
| Firebase project (Crashlytics) | Phase 12 | No |
| Google Play developer account ($25 one-time) | Post-v1 | No |

### Next Phase
**Phase 2 — Product & Technical Architecture.** Produce: the full Room schema for all ~25 entities with relationships and indices; the architecture diagram; repository interfaces; the navigation graph; the state-management contract; the offline and (future) sync strategy; and the seeding strategy for the exercise library and CNF/USDA generic foods.

**No application code until Phase 2 is approved.**

---

## 9. Addendum — Personal-Use Scope & Label OCR (2026-08-11, revision 2)

Two constraints changed after the initial research: **(a) the app is for personal use and will not be sold or distributed**, and **(b) maximum food coverage is the priority, with camera-based nutrition-label reading as the fallback when no database has the product.**

### 9.1 What personal use changes

Most of §7's licensing risk was risk *of distribution*. Nothing in ODbL, CC-BY-SA, or AGPL restricts what you do with data on your own device for your own use — those licenses trigger on **redistribution and public use of a derived database**, neither of which happens here.

| Earlier conclusion | Revised |
|---|---|
| `free-exercise-db` images unusable (§4.1, §7) | ✅ **Use them.** The provenance concern was commercial-distribution risk. Private use is fine. This removes the original-illustration workload from Phase 7 — a real scope saving. |
| wger exercise data avoided (CC-BY-SA share-alike) | ✅ **Usable** as a gap-filler alongside `free-exercise-db`. |
| OFF product images referenced by URL only, never cached | ✅ **Cache them locally** for offline use. |
| Bundling an OFF data subset "deliberately deferred" (§6.2, §7) | ✅ **Do it** — see §9.3. This is now the single biggest coverage win. |
| Attribution required in-app | Keep a credits screen anyway — it costs nothing and keeps the door open if this is ever published. |
| Paid-API free tiers "unusable in production" (§2) | ⚠️ **Re-evaluated — see §9.2.** The verdict inverts for a single user. |

**The one thing that doesn't change:** if this is ever published or shared, §7's analysis applies again in full. The architecture keeps external data source-tagged (§6.1) regardless, so that door stays open at near-zero cost.

### 9.2 Free API tiers are viable at single-user volume

§2 dismissed the commercial APIs because their free tiers can't serve a user base. For **one** user those same tiers are generous — a heavy day is maybe 20–30 lookups.

| Service | Free tier | Per-user headroom | Coverage added |
|---|---|---|---|
| **Open Food Facts** | Unlimited (15 req/min) | Effectively infinite | Primary — global + best Canadian barcode coverage |
| **FatSecret Basic** | **5,000 calls/day** | ~200× a heavy day | Large curated barcode DB — **but Basic is US-dataset only** |
| **Nutritionix** | 200 calls/day | ~10× a heavy day | Strong US branded + **restaurant menu items**, which OFF lacks entirely |
| **Edamam** | 1,000/day (50/min) | ~40× | Good text/NLP food parsing ("2 eggs and toast") |
| **USDA FDC** | 1,000/hour | ~30× | Generic whole foods, CC0 |
| **CNF** | Open data | Unlimited | Canadian generic foods |

**Revised food-lookup cascade** (each step only runs if the previous found nothing):

```
local cache  →  bundled OFF subset  →  OFF live  →  FatSecret  →  Nutritionix
             →  USDA / CNF (generic foods)  →  LABEL OCR (§9.4)  →  manual entry
```

Practical notes: FatSecret and Nutritionix both need a free developer account and require attribution on their tiers — trivial here. Each provider goes behind one `FoodDataSource` interface so adding or dropping one is a repository-level change. Their keys go in `local.properties` → BuildConfig, gitignored (§6.6) — and per your standing rule, **this project lives outside OneDrive**, so keys never sync to the cloud.

Restaurant/menu items are worth calling out: Nutritionix is the only source here that covers them, and no barcode or label exists for a restaurant meal. That alone justifies wiring it in.

### 9.3 Bundled offline database — the biggest coverage win

Download the OFF JSONL dump (~7 GB gzipped, ~43 GB expanded), filter it once on the desktop, and ship the result as a downloadable asset the app fetches on first run.

**Filter:** products with a Canadian or US country tag **and** a barcode **and** at least energy + the three macros. Keep only the ~15 fields actually used (barcode, name, brand, serving size, kcal, P/C/F, fibre, sugar, sodium, sat fat, image URL). Estimated result: **~150k–250k products, roughly 40–80 MB** as a pre-built Room database file — a rounding error on a modern phone.

The payoff: **barcode scanning works fully offline, instantly, with no network round-trip**, for the overwhelming majority of products either of us would actually scan. Live OFF becomes the fallback for the long tail rather than the primary path. This is a Phase 3 task (one-time desktop script + asset packaging), not app code.

### 9.4 Nutrition-label OCR — "scan anything"

This is the feature that makes the scanner never fail. When no database has the barcode, the user photographs the Nutrition Facts panel and the app reads it.

**Two tiers, both real:**

| Tier | Engine | Cost | Works offline | Role |
|---|---|---|---|---|
| **A — primary** | **ML Kit Text Recognition v2** (on-device) | Free | ✅ Yes | Every scan starts here |
| **B — fallback** | **Claude vision** (`claude-haiku-4-5`) | ~¼¢ per label | ❌ No | One-tap "Couldn't read it? Try harder" |

**Tier A: ML Kit Text Recognition v2.** Same CameraX pipeline as the barcode scanner, so it's an added analyzer rather than a new subsystem — on-device, no network, no per-scan cost, nothing leaves the phone. Accuracy is driven by pixel density: ML Kit wants **≥16×16 px per character**, and cropping to the panel both speeds it up and improves accuracy. So the capture UI shows a guide rectangle, and only that crop is fed to the recognizer.

**Parsing is geometric, not textual.** A Nutrition Facts table is a two- or three-column layout (nutrient name | amount | % DV), and ML Kit returns every line with a bounding box. Reading the raw text stream in order produces garbage; matching by **row** (y-axis overlap of bounding boxes) produces clean pairs. The algorithm:

1. **Anchor** — find `Nutrition Facts` / `Valeur nutritive` / `Calories` to locate the panel and its orientation.
2. **Serving size** — parse `Per 1 cup (250 mL)` / `pour 3/4 tasse` / `Serving size 2/3 cup (55g)`. Capture both the household measure and the metric weight; the metric weight is what scaling math uses.
3. **Per nutrient**, search a bilingual synonym table, then take the first `number + unit` token sharing that row:

   | Field | English | French (mandatory on Canadian labels) |
   |---|---|---|
   | Fat | Fat, Total Fat | Lipides |
   | Saturated | Saturated, Saturated Fat | saturés |
   | Trans | Trans | trans |
   | Carbs | Carbohydrate, Total Carbohydrate | Glucides |
   | Fibre | Fibre, Dietary Fiber | Fibres |
   | Sugars | Sugars, Total Sugars | Sucres |
   | Protein | Protein | Protéines |
   | Sodium | Sodium | Sodium |
   | Cholesterol | Cholesterol | Cholestérol |
   | Potassium | Potassium | Potassium |

4. **Discard `%` tokens** — % Daily Value sits in the same row and is the #1 source of mis-parses (grabbing `15%` instead of `12 g`).
5. **Calories** — bare integer on the Calories row, no unit.
6. **Validate** — `4·protein + 4·carbs + 9·fat` vs stated kcal. Within ~10% → high confidence (green); 10–25% → medium (amber); beyond → low (red, flag every field).
7. **Multi-frame voting** — run OCR across ~5 consecutive frames and take the modal value per field. This is free and is the single biggest accuracy improvement available; a glare-corrupted digit in one frame gets outvoted.
8. **Review sheet** — every field pre-filled and editable, shown beside the captured crop, with low-confidence fields highlighted. **The user always confirms before saving.** No silent OCR result ever becomes logged data.

Canadian labels are a good OCR target: the CFIA mandates the format, layout, and typography, so the panel is highly regular — bilingual (which the synonym table handles), always per-serving, always with %DV. US labels differ mainly in wording (`Serving size`, `Amount per serving`, `Added Sugars`) and are covered by the same synonym table.

**Tier B: Claude vision fallback.** For a crumpled, curved, glare-hit, or oddly-formatted panel where OCR + voting still fails, one button sends the cropped image to the Claude API with a structured-output schema and gets back a clean nutrition object. Model: **`claude-haiku-4-5`** — vision-capable, cheapest at **$1 / $5 per MTok**. A label image is ~1,600 input tokens and the JSON reply ~200 output tokens, so **≈ $0.0026 per label — about a quarter of a cent.** Even 5 fallback scans a day is under $0.50/month. Requires an Anthropic API key (yours, in `local.properties`) and network. This is a Phase 4 nice-to-have, gated behind a settings toggle and off by default — Tier A should handle the large majority.

**Store the captured label photo** with the custom food. It costs a few hundred KB, and being able to re-check the original beats re-scanning the product.

**Resulting scan flow — no dead ends anywhere:**

```
SCAN BARCODE
 ├─ found (local / bundled / OFF / FatSecret / Nutritionix)  → confirm → log
 └─ not found anywhere
     └─ "We don't know this one — point the camera at the nutrition label"
         ├─ OCR reads it (Tier A, ~5-frame vote)  → review sheet → save + log
         ├─ OCR partial                            → review sheet, gaps highlighted → user fills → save
         ├─ OCR fails → "Try harder" (Tier B)      → review sheet → save
         └─ still nothing                          → manual entry, barcode pre-filled
     Saved against the barcode → every future scan of it resolves instantly, offline.
```

### 9.5 Revised recommendation

Everything in §8 stands, with four changes:

1. **Ship a bundled offline OFF subset** (CA+US, ~40–80 MB) — biggest single coverage and speed win, and it makes barcode scanning work with no network at all.
2. **Layer the free API tiers** — FatSecret and Nutritionix behind OFF, plus Nutritionix for restaurant items that have no barcode or label. Free at one-user volume.
3. **Build label OCR as a first-class feature, not a fallback afterthought** — ML Kit Text Recognition v2 on-device with geometric row-matching, bilingual synonyms, macro-vs-calorie validation, multi-frame voting, and a mandatory user-confirmation sheet. Optional Claude Haiku 4.5 vision as a paid-but-negligible second tier.
4. **Use the free exercise images and wger data** — personal use removes the licensing objection and drops the original-illustration work from Phase 7.

Net effect on cost: still **$0/month** in the normal path; at most cents per month if the Claude fallback is enabled and used.

---

## 10. Addendum — Exhaustive Zero-Cost Sweep (2026-08-11, revision 3)

**Constraint: no ongoing cost, ever.** This section is the final search across every category. The conclusion is that **PULSE can be built with $0 ongoing cost and no mandatory external service** — and, importantly, it comes out *better* than the cost-tolerant design, because the winning approach is bundled offline data rather than API calls.

### 10.1 The headline find: OpenNutrition Foods

**[OpenNutrition](https://www.opennutrition.app/download) is the single most valuable thing in this entire research pass.** It is a **300,000+ food dataset that has already merged USDA + Canadian Nutrient File + FRIDA (Denmark) + AUSNUT (Australia) + Open Food Facts**, cleaned and normalized, **including EAN-13 barcodes**, distributed as a downloadable TSV in a ZIP.

This is exactly the merge job I was going to write a desktop script to do in §9.3 — already done, already deduplicated, already normalized. License is **ODbL** with a modified DbCL, attribution to "OpenNutrition" (with link) plus "© Open Food Facts contributors" where OFF-sourced. Attribution is a credits screen; there is no cost, no key, no rate limit, and no network dependency.

**Revised plan:** bundle OpenNutrition as the **primary** database and layer an Open Food Facts CA/US barcode subset on top of it for retail-product depth. Both ship as pre-built Room database files.

### 10.2 Complete zero-cost stack

| Need | Choice | Cost | Ongoing dependency |
|---|---|---|---|
| Generic + international foods | **OpenNutrition** bundled (300k+, ODbL) | $0 | None — offline file |
| Retail barcode products | **OFF CA/US subset** bundled + live OFF for long tail | $0 | None (live is optional) |
| US generic foods | Already inside OpenNutrition (USDA) | $0 | None |
| Canadian generic foods | Already inside OpenNutrition (CNF) | $0 | None |
| Barcode scanning | **ML Kit Barcode** (on-device) | $0 | None |
| Label reading | **ML Kit Text Recognition v2** (on-device) | $0 | None |
| Exercise text data | **`free-exercise-db`** (Unlicense) | $0 | None — bundled |
| Exercise animations | Free GIF datasets (see §10.4) | $0 | None — bundled/lazy |
| Charts | **Vico** (Apache 2.0) | $0 | None |
| Backup / "sync" | **Local export + Google Drive appDataFolder** (§10.5) | $0 | User's own Drive |
| Crash reporting | **ACRA** (open source) or none | $0 | None |
| Analytics | None | $0 | None |
| Auth / cloud DB | None | $0 | None |

**Ongoing cost: $0. Mandatory network calls: zero.** The app is fully functional with the phone in airplane mode from first launch.

### 10.3 Optional free-tier live APIs (all $0, none required)

These are pure upside for the long tail. Every one is free at single-user volume; the app degrades gracefully if any disappears, because the bundled data is the primary source.

| Service | Free allowance | Key needed | Adds |
|---|---|---|---|
| **Open Food Facts** | Unlimited (15/min) | No | Newest products, global |
| **FatSecret Basic** | 5,000/day | Yes (free) | Curated US barcodes |
| **Nutritionix** | 200/day | Yes (free) | **Restaurant menu items** — nothing else covers these |
| **Edamam** | 1,000/day | Yes (free) | Natural-language parsing ("2 eggs and toast") |
| **USDA FDC** | 1,000/hour | Yes (free) | Freshest USDA data |
| **API Ninjas Nutrition** | Free tier | Yes (free) | NL food queries (absorbed CalorieNinjas in 2025) |
| **UPCitemdb** | 100/day, no key | No | Product *name* when nutrition is unknown — turns a dead scan into a pre-filled form |

Rejected: **Chomp** (commercial, no free tier), **Spoonacular** (recipe-oriented, wrong tool), **Go-UPC / Barcode Lookup** (paid).

That last one is a nice trick — UPCitemdb needs no key and will usually return a product *name* even when no nutrition database knows the item, so the "create this food" screen opens pre-filled with the right name and brand instead of blank.

### 10.4 Label OCR without paying for AI

Dropping the Claude fallback from §9.4 costs less accuracy than expected, because the free tiers do the heavy lifting:

1. **ML Kit Text Recognition v2** — free, on-device, offline. Primary.
2. **Multi-frame voting** across ~5 frames — free, and the single largest accuracy gain available.
3. **Geometric row-matching + bilingual synonyms + %-token rejection + macro/calorie validation** (all as specced in §9.4) — free, just code.
4. **Mandatory review sheet** — the user confirms every field anyway, so residual OCR error is caught by a human, not shipped into the log.

**If an AI fallback is still wanted later, it is free rather than cheap:** the **Gemini API free tier** gives 1,500 requests/day with vision, no credit card, no expiry — vastly more than a person could use. One caveat worth stating plainly: on the *free* tier Google's terms permit using inputs to improve their models. For photographs of retail packaging that is low-sensitivity, but it is a real difference from the on-device path, so this stays **off by default behind an explicit toggle**. Not in v1.

### 10.5 Backup and "sync" for free

Real cloud sync needs a server. But the actual goal — *don't lose my data, and let me move phones* — has two free answers, and PULSE should have both:

1. **Local export/import** (v1). Write a single `.pulse` file (zipped SQLite + photos) anywhere via the Storage Access Framework. Zero dependencies, works forever, and doubles as the "your data is yours" feature.
2. **Google Drive `appDataFolder`** (Phase 6+). A hidden, app-private folder in *your own* Drive — the same mechanism WhatsApp uses. Free (consumes the user's existing 15 GB, not a bill to us), no server, no account system of ours. Gives one-tap "Back up now" and restore-on-new-device.

Also available free: **Android Auto Backup** (25 MB/app, doesn't touch the user's quota) — too small for photos and a full food database, but perfect for settings, goals, and profile. Use it for those.

None of these is multi-device *live* sync. That genuinely requires a backend, and it stays out of scope.

### 10.6 Exercise media

`free-exercise-db` (Unlicense) covers names, muscles, equipment, and instructions. For animations, several free GIF datasets exist — `hasaneyldrm/exercises-dataset` (1,324 exercises with GIFs, 180×180 thumbnails, 6 languages), `FitnessDB/exercise-animation-dataset` (1,500+), and `ExerciseDB` (11,000+). Their licenses are as murky as `free-exercise-db`'s images, which under personal-use scope (§9.1) is fine.

**Bundling strategy:** ship the 180×180 thumbnails in the APK (small, always available) and lazy-download full GIFs on first view into the local cache. Keeps install size sane without giving up the animations.

### 10.7 Final zero-cost recommendation

Build on **bundled offline datasets as the primary source**, not API calls. That is cheaper (free), faster (no round-trip), more reliable (no third-party uptime), more private (nothing leaves the device), and fully offline — every optional live API is then a bonus for the long tail rather than a dependency.

**Total ongoing cost: $0. Required accounts: none. Required network: none.**

---

## Sources

- [Open Food Facts — Terms of use, contribution and re-use](https://world.openfoodfacts.org/terms-of-use)
- [Open Food Facts — API documentation (rate limits, User-Agent policy)](https://openfoodfacts.github.io/openfoodfacts-server/api/)
- [Open Food Facts — Data, API and SDKs (bulk dumps)](https://world.openfoodfacts.org/data)
- [Open Food Facts — Canada](https://ca.openfoodfacts.org/)
- [USDA FoodData Central API Guide](https://fdc.nal.usda.gov/api-guide)
- [Canadian Nutrient File (CNF) API Guide — Health Canada](https://produits-sante.canada.ca/api/documentation/cnf-documentation-en.html)
- [Canadian Nutrient File — Open Government Portal](https://open.canada.ca/data/en/dataset/1b6139bd-ed7e-4043-bc28-ff00e10f3109)
- [Nutritionix — Nutrition API](https://www.nutritionix.com/api) · [Database Licensing](https://www.nutritionix.com/database)
- [FatSecret Platform — API Editions](https://platform.fatsecret.com/api-editions)
- [Edamam — Food Database API](https://developer.edamam.com/food-database-api)
- [Spoonacular — Food API pricing](https://spoonacular.com/food-api/pricing)
- [Google ML Kit — Barcode Scanning for Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- [Scanbot — ML Kit vs. ZXing comparison](https://scanbot.io/blog/ml-kit-vs-zxing/)
- [yuhonas/free-exercise-db](https://github.com/yuhonas/free-exercise-db) · [Issue #12 — image licensing (unanswered)](https://github.com/yuhonas/free-exercise-db/issues/12)
- [wger-project/wger](https://github.com/wger-project/wger)
- [Android Developers — Health Connect](https://developer.android.com/health-and-fitness/health-connect)
- [Google ML Kit — Text Recognition v2 for Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [CFIA — Nutrition Facts table formats](https://inspection.canada.ca/en/food-labels/labelling/industry/nutrition-labelling/nutrition-facts-table-formats)
- [Health Canada — Directory of Nutrition Facts Table Formats](https://www.canada.ca/en/health-canada/services/technical-documents-labelling-requirements/directory-nutrition-facts-table-formats.html)
- [FatSecret Platform — Upgrade / tier limits](https://platform.fatsecret.com/upgrade-account)
- [Open Food Facts — JSONL data export](https://huggingface.co/datasets/openfoodfacts/openfoodfacts-jsonl-export)
- [OpenNutrition — dataset download & licence](https://www.opennutrition.app/download)
- [UPCitemdb — free UPC lookup API](https://devs.upcitemdb.com/)
- [API Ninjas — Nutrition API](https://api-ninjas.com/api/nutrition)
- [Android Developers — Auto Backup](https://developer.android.com/identity/data/autobackup)
- [ACRA — Application Crash Reports for Android](https://github.com/ACRA/acra)
- [hasaneyldrm/exercises-dataset — 1,324 exercises with GIFs](https://github.com/hasaneyldrm/exercises-dataset)
- [FitnessDB/exercise-animation-dataset](https://github.com/FitnessDB/exercise-animation-dataset)
