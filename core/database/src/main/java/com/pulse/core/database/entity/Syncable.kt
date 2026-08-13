package com.pulse.core.database.entity

/**
 * Sync-readiness contract for every user-owned table
 * (PHASE2_ARCHITECTURE.md §8.2).
 *
 * There is **no sync code in v1** — these are four columns and zero logic. They
 * exist now because they cannot be retrofitted cheaply later:
 *
 * - IDs are client-generated UUIDs. Autoincrement integers make offline
 *   creation unsyncable, and rewriting primary keys across a populated
 *   database is exactly the migration we never want to write.
 * - [updatedAt] is epoch millis UTC, for last-write-wins reconciliation.
 * - [isDeleted] is a soft delete. A hard delete cannot be propagated to another
 *   device, so rows are tombstoned instead.
 * - [syncState] is written but unread in v1.
 *
 * Reference data (bundled foods, seeded exercises) is exempt: it is
 * re-derivable and never syncs.
 */
interface Syncable {
    val createdAt: Long
    val updatedAt: Long
    val isDeleted: Boolean
    val syncState: String
}

object SyncState {
    /** No local change pending. Everything bundled or already reconciled. */
    const val SYNCED = "SYNCED"

    /** Local change not yet pushed. Unused in v1. */
    const val PENDING = "PENDING"
}

object FoodSource {
    const val OPENNUTRITION = "OPENNUTRITION"
    const val OPEN_FOOD_FACTS = "OFF"
    const val USDA = "USDA"
    const val CNF = "CNF"

    /** User-created or user-edited. The only source that ever syncs. */
    const val USER = "USER"

    /** Materialized from a recipe so it logs like any other food. */
    const val RECIPE = "RECIPE"

    /** Everything the user owns and we must never silently overwrite. */
    val USER_OWNED = setOf(USER, RECIPE)
}

object MealType {
    const val BREAKFAST = "BREAKFAST"
    const val LUNCH = "LUNCH"
    const val DINNER = "DINNER"
    const val SNACK = "SNACK"

    /** Display order — meals sort by time of day, not alphabetically. */
    val ORDERED = listOf(BREAKFAST, LUNCH, DINNER, SNACK)
}

/**
 * Drives the entire set-logging UI. Without it, every cardio, mobility and
 * bodyweight case becomes a special-cased screen (PHASE2_ARCHITECTURE.md §4.4).
 */
object TrackingMode {
    const val WEIGHT_REPS = "WEIGHT_REPS"                     // bench press
    const val REPS_ONLY = "REPS_ONLY"                         // pull-up
    const val WEIGHTED_BODYWEIGHT = "WEIGHTED_BODYWEIGHT"     // weighted dip
    const val ASSISTED_BODYWEIGHT = "ASSISTED_BODYWEIGHT"     // assisted pull-up
    const val DURATION = "DURATION"                           // plank
    const val DURATION_WEIGHT = "DURATION_WEIGHT"             // farmer's carry
    const val DISTANCE_DURATION = "DISTANCE_DURATION"         // running, cycling

    val ALL = setOf(
        WEIGHT_REPS, REPS_ONLY, WEIGHTED_BODYWEIGHT, ASSISTED_BODYWEIGHT,
        DURATION, DURATION_WEIGHT, DISTANCE_DURATION,
    )
}

object SetType {
    const val NORMAL = "NORMAL"
    const val WARMUP = "WARMUP"
    const val DROP = "DROP"
    const val FAILURE = "FAILURE"
}

object MeasurementType {
    const val WEIGHT = "WEIGHT"           // kg
    const val BODY_FAT = "BODY_FAT"       // percent
    const val WAIST = "WAIST"             // cm
    const val CHEST = "CHEST"
    const val HIPS = "HIPS"
    const val NECK = "NECK"
    const val ARM_L = "ARM_L"
    const val ARM_R = "ARM_R"
    const val THIGH_L = "THIGH_L"
    const val THIGH_R = "THIGH_R"
    const val CALF_L = "CALF_L"
    const val CALF_R = "CALF_R"
    const val CUSTOM = "CUSTOM"
}

object RecordType {
    const val MAX_WEIGHT = "MAX_WEIGHT"
    const val MAX_REPS = "MAX_REPS"
    const val EST_ONE_RM = "EST_ONE_RM"
    const val MAX_VOLUME_SET = "MAX_VOLUME_SET"
    const val MAX_DISTANCE = "MAX_DISTANCE"
    const val BEST_PACE = "BEST_PACE"
    const val LONGEST_DURATION = "LONGEST_DURATION"
}
