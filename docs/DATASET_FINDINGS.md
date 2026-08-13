# OpenNutrition Dataset — Verified Findings

**Date:** 2026-08-11
**Dataset:** `opennutrition-dataset-2025.1.zip` (62,927,029 bytes)
**SHA-256:** `30420802bbf0e29852c282e37a58c7e18ebc1b57e109706925ef969f0498ff47` — verified against publisher checksum
**Extracted:** `opennutrition_foods.tsv`, 282,413,682 bytes, **326,759 rows**
**Verified by:** `tools/inspect_opennutrition.py`, `tools/validate_opennutrition.py` (full-file scan, no sampling)

This resolves the Phase 2 blocker *"OpenNutrition's field mapping is unverified."* Every number below is measured, not assumed.

---

## 1. Headline results

| Metric | Result | Phase 2 assumption | Verdict |
|---|---|---|---|
| Total foods | **326,759** | "~300k" | ✅ Confirmed |
| Valid 13-digit barcodes | **313,442 (95.9%)** | "includes EAN-13" | ✅ **Far better than expected** |
| kcal + all 3 macros | **326,759 (100.0%)** | "assumed complete" | ✅ **Exceptional** |
| Usable metric serving | **326,759 (100.0%)** | "assumed present" | ✅ Confirmed |
| Values per 100 g | Confirmed | assumed | ✅ Confirmed |
| Negative values | **0** | — | ✅ Clean |
| Duplicate barcodes | 186 (313,256 distinct) | — | ⚠️ Index must not be UNIQUE |
| Impossible kcal (>900/100 g) | 345 | — | ⚠️ Needs a sanitizer |

**100% completeness on calories and all three macros is materially better than Open Food Facts**, where missing macros were the top product risk identified in Phase 1 §2.2. Every row in this dataset is loggable.

## 2. Verified field mapping

My Phase 2 key guesses were wrong on four of ten fields. Corrected and confirmed:

| OpenNutrition key | PULSE column | Coverage | Note |
|---|---|---|---|
| `calories` | `kcalPer100` | **100.0%** | ⚠️ not `energy` |
| `protein` | `proteinPer100` | **100.0%** | |
| `carbohydrates` | `carbsPer100` | **100.0%** | ⚠️ not `carbs` |
| `total_fat` | `fatPer100` | **100.0%** | ⚠️ not `fat` |
| `sodium` | `sodiumMgPer100` | 99.8% | mg |
| `total_sugars` | `sugarPer100` | 94.7% | ⚠️ not `sugar` |
| `saturated_fats` | `satFatPer100` | 87.0% | ⚠️ not `saturated_fat` |
| `cholesterol` | `cholesterolMgPer100` | 85.8% | mg |
| `dietary_fiber` | `fiberPer100` | 84.4% | ⚠️ not `fiber` |
| `potassium` | `potassiumMgPer100` | 44.9% | mg |

Also present and useful: `trans_fats`, `added_sugars`, `soluble_fiber`, `insoluble_fiber`, `sugar_alcohols`, `monounsaturated_fats`, `polyunsaturated_fats`.

**97 distinct nutrient keys exist in total** — full vitamin, mineral, and amino-acid profiles on many foods. See §4.1.

## 3. Structure of the JSON columns

**`serving`** — present on 100% of rows, always the same shape:

```json
{"common": {"unit": "slice", "quantity": 1}, "metric": {"unit": "g", "quantity": 45}}
```

This maps *directly* onto `food_serving`: `label = "1 slice"`, `gramWeight = 45`. No inference needed.

**`metric.unit` is `g` (282,643) or `ml` (44,070)** — which means **`isLiquid` is derivable exactly**, not guessed. That removes the `densityGPerMl` guesswork from the Phase 2 schema for this dataset.

Top `common` units: `g` (159,685), `cup` (34,680), `oz` (25,121), `ml` (21,956), `tbsp` (17,193), `piece`, `bar`, `slice`, `tsp`, `fl oz`, `package`, `cookie`, `can`, `bottle`, `container`.

**`package_size`** — same shape, present on ~3,400 rows. Enables an automatic "1 package" serving.

**`source`** — JSON array of provenance records, confirming the merge claim:

```json
[{"database": "USDA Foundational Foods", "id": 331960, "reference": "FDC ID", "name": "...", "url": "..."},
 {"database": "Canadian Nutrient File", "id": "502554", ...}]
```

Observed databases: **USDA Foundational Foods, USDA Standard Reference Legacy, Canadian Nutrient File, Frida (Denmark), Australian Nutrient Database.** Confirms Phase 1 §10.1.

**`ingredient_analysis`** — allergen and additive analysis, already extracted:

```json
{"gluten": ["gluten","wheat","barley","rye"], "allergen_wheat": ["wheat"], "allergen_sesame": ["sesame"], "added_sugars": ["sugar"]}
```

**`alternate_names`** — JSON array of synonyms (e.g. Large Eggs → `["eggs large","jumbo eggs","extra large eggs","xl eggs","grade aa large eggs"]`).

## 4. Schema changes this forces

### 4.1 Add `micronutrientsJson`
97 nutrient keys exist; the Phase 2 schema captures 10. Adding 87 columns is absurd, and discarding them is wasteful. **Store the remainder as a JSON blob** in a nullable `micronutrientsJson TEXT` column. Costs nothing now, enables a micronutrient screen later with no migration.

### 4.2 `alternate_names` must feed the FTS index
This is a real search-quality win and I'd have missed it. Searching "xl eggs" should find "Large Eggs". **`food_fts` indexes `name + brand + alternateNames`.**

### 4.3 Add `foodType` for search ranking
`type` ∈ `grocery` (313,442) / `everyday` (5,299) / `restaurant` (4,182) / `prepared` (3,836).

**Only `grocery` rows carry barcodes** — the other three are unbranded generics and restaurant items. This matters for ranking: a bare query for "chicken breast" should surface the `everyday` generic, not the 400th branded chicken product. Add `foodType TEXT` and weight it in search ordering.

### 4.4 Restaurant coverage is better than assumed
**4,182 restaurant items** are included. Phase 1 §10.3 said Nutritionix was the only source for restaurant food — partially wrong. Nutritionix is still broader, but it drops from "the only option" to "a bonus". Reinforces that no live API is required.

### 4.5 Barcode index must not be UNIQUE
186 duplicate barcodes exist (multi-pack/variant collisions). The Phase 2 schema already specified a non-unique index; this confirms resolution must pick the best row by `dataConfidence`, not assume one match.

### 4.6 Sanitizer required on import
345 rows report >900 kcal/100 g, which is physically impossible (pure fat ≈ 900). Ingest rule: **flag `dataConfidence = LOW`, never drop** — the food still exists and the user can correct it.

## 5. Confidence-score calibration

Running the Phase 2 §6.3 rule (`4P + 4C + 9F` vs stated kcal) against all 312,216 checkable rows:

| Band | Rule | Count | Share |
|---|---|---|---|
| **HIGH** | within 10% | 265,185 | **84.9%** |
| MEDIUM | 10–25% | ~36,564 | 11.7% |
| **LOW** | beyond 25% | 10,467 | **3.4%** |

The rule works as designed and the distribution is healthy — the overwhelming majority are HIGH, and the 3.4% LOW tail is exactly the set that should be flagged for user verification. **No recalibration needed.**

## 6. Licensing note (verified from bundled LICENSE files)

ODbL for the database, modified DbCL for contents. Attribution requirement is stricter than I summarised in Phase 1 §10.1: it demands attribution **on every screen displaying the data**, plus store listing, website, and legal section — explicitly stating that consolidated attribution does *not* satisfy it. Share-alike applies to derivative databases.

Under personal-use scope (Phase 1 §9.1) none of this binds. A credits screen goes in regardless, so the door to publishing stays open — but **if PULSE is ever distributed, the per-screen attribution requirement must be revisited**, as it materially affects UI design.

## 7. Conclusion

**The dataset is better than the Phase 2 design assumed, and no architectural decision is invalidated.** The four schema deltas above (§4.1–4.3, plus the sanitizer) are additive. The importer can proceed.

Notably, 100% macro completeness plus 95.9% barcode coverage means the bundled database alone answers the overwhelming majority of scans and searches — reinforcing the Phase 1 §10.7 decision to treat bundled data as primary and every live API as optional.
