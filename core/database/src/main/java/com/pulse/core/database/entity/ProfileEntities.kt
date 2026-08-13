package com.pulse.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single-row profile. All measurements metric (PHASE2_ARCHITECTURE.md §1.1) —
 * the unit fields are display preferences only and never affect storage.
 */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: String = SINGLETON_ID,
    val displayName: String? = null,
    /** MALE | FEMALE | UNSPECIFIED — a BMR formula input, nothing more. */
    val sex: String = "UNSPECIFIED",
    /** Epoch day. */
    val birthDate: Long? = null,
    val heightCm: Double? = null,
    /** SEDENTARY | LIGHT | MODERATE | ACTIVE | VERY_ACTIVE */
    val activityLevel: String = "MODERATE",
    /** LOSE | MAINTAIN | GAIN */
    val goalType: String = "MAINTAIN",
    /** Signed; 0 for MAINTAIN. */
    val rateKgPerWeek: Double = 0.0,
    val goalWeightKg: Double? = null,

    val massUnit: String = "KILOGRAMS",
    val lengthUnit: String = "CENTIMETRES",
    val volumeUnit: String = "MILLILITRES",
    val energyUnit: String = "KCAL",

    val themeMode: String = "SYSTEM",
    val weekStartsOn: String = "MONDAY",

    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean = false,
    override val syncState: String = SyncState.SYNCED,
) : Syncable {
    companion object {
        const val SINGLETON_ID = "me"
    }
}

/**
 * Effective-dated, append-only nutrition targets.
 *
 * Never updated in place: changing today's calorie target must not retroactively
 * change whether last Tuesday was on target. A query resolves the latest row
 * with `effectiveFrom <= date`.
 */
@Entity(tableName = "goal_target", indices = [Index("effectiveFrom")])
data class GoalTargetEntity(
    @PrimaryKey val id: String,
    /** Epoch day this target takes effect. */
    val effectiveFrom: Long,
    val calorieTarget: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val waterMl: Int,
    /** CALCULATED | MANUAL */
    val source: String,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean = false,
    override val syncState: String = SyncState.SYNCED,
) : Syncable

/**
 * One row per measurement reading. A [type] enum rather than a wide row per
 * date, so custom measurements, sparse logging (weight daily, waist monthly)
 * and new measurement types all work without a migration.
 *
 * [value] is kg for WEIGHT, cm for lengths, percent for BODY_FAT.
 */
@Entity(tableName = "body_measurement", indices = [Index("type", "date"), Index("date")])
data class BodyMeasurementEntity(
    @PrimaryKey val id: String,
    /** Epoch day. */
    val date: Long,
    val type: String,
    val customLabel: String? = null,
    val value: Double,
    val note: String? = null,
    val photoPath: String? = null,
    val loggedAt: Long,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean = false,
    override val syncState: String = SyncState.SYNCED,
) : Syncable

/**
 * Barcodes scanned while offline that no local source could resolve. Retried by
 * a background worker on connectivity regained — the user is never blocked and
 * can create the food manually in the meantime.
 */
@Entity(tableName = "pending_lookup", indices = [Index("barcode")])
data class PendingLookupEntity(
    @PrimaryKey val barcode: String,
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    val createdAt: Long,
)
