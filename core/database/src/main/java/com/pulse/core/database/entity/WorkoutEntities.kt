package com.pulse.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Exercise library. Seeded from a curated set and extensible by the user.
 *
 * [trackingMode] is the field that lets one workout engine cover strength,
 * cardio and mobility — see [TrackingMode].
 */
@Entity(
    tableName = "exercise",
    indices = [Index("primaryMuscle"), Index("equipment"), Index("category"), Index("name")],
)
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val primaryMuscle: String,
    /** JSON array of secondary muscles. */
    val secondaryMuscles: String? = null,
    val equipment: String,
    /** STRENGTH | CARDIO | MOBILITY | PLYOMETRIC | SPORT */
    val category: String,
    val difficulty: String? = null,
    val trackingMode: String,
    /** JSON array of instruction steps. */
    val instructions: String? = null,
    val thumbnailPath: String? = null,
    val animationPath: String? = null,
    val isUserCreated: Boolean = false,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean = false,
    override val syncState: String = SyncState.SYNCED,
) : Syncable

@Fts4(contentEntity = ExerciseEntity::class)
@Entity(tableName = "exercise_fts")
data class ExerciseFtsEntity(
    val name: String,
)

@Entity(tableName = "workout_template")
data class WorkoutTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val notes: String? = null,
    val lastPerformedAt: Long? = null,
    val sortOrder: Int = 0,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean = false,
    override val syncState: String = SyncState.SYNCED,
) : Syncable

@Entity(
    tableName = "template_exercise",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
        ),
    ],
    indices = [Index("templateId"), Index("exerciseId")],
)
data class TemplateExerciseEntity(
    @PrimaryKey val id: String,
    val templateId: String,
    val exerciseId: String,
    val sortOrder: Int = 0,
    /** Exercises sharing a group number are performed as a superset. */
    val supersetGroup: Int? = null,
    val notes: String? = null,
)

/** Prescribed sets, so a template can say "3 × 8 @ 100 kg" rather than just "3 sets". */
@Entity(
    tableName = "template_set",
    foreignKeys = [
        ForeignKey(
            entity = TemplateExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateExerciseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("templateExerciseId")],
)
data class TemplateSetEntity(
    @PrimaryKey val id: String,
    val templateExerciseId: String,
    val setIndex: Int,
    val setType: String = SetType.NORMAL,
    val targetReps: Int? = null,
    val targetWeightKg: Double? = null,
    val targetDurationSec: Int? = null,
    val targetDistanceM: Double? = null,
)

/**
 * A workout session. [finishedAt] null means in progress — which is how an
 * interrupted session survives process death and is offered for resume.
 */
@Entity(
    tableName = "workout",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("startedAt"), Index("finishedAt"), Index("templateId")],
)
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val name: String,
    val templateId: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val notes: String? = null,
    val totalVolumeKg: Double? = null,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean = false,
    override val syncState: String = SyncState.SYNCED,
) : Syncable

@Entity(
    tableName = "workout_exercise",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
        ),
    ],
    indices = [Index("workoutId"), Index("exerciseId")],
)
data class WorkoutExerciseEntity(
    @PrimaryKey val id: String,
    val workoutId: String,
    val exerciseId: String,
    val sortOrder: Int = 0,
    val supersetGroup: Int? = null,
    val notes: String? = null,
)

/**
 * One set. Which columns are populated depends on the exercise's
 * [TrackingMode] — a plank fills [durationSec], a run fills [distanceM] and
 * [durationSec], a bench press fills [weightKg] and [reps].
 */
@Entity(
    tableName = "workout_set",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutExerciseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workoutExerciseId")],
)
data class WorkoutSetEntity(
    @PrimaryKey val id: String,
    val workoutExerciseId: String,
    val setIndex: Int,
    val setType: String = SetType.NORMAL,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val distanceM: Double? = null,
    val rpe: Double? = null,
    val restSec: Int? = null,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
)

@Entity(
    tableName = "personal_record",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("exerciseId", "recordType")],
)
data class PersonalRecordEntity(
    @PrimaryKey val id: String,
    val exerciseId: String,
    val recordType: String,
    val value: Double,
    /** e.g. reps alongside weight for an estimated 1RM. */
    val secondaryValue: Double? = null,
    val workoutSetId: String? = null,
    val achievedAt: Long,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean = false,
    override val syncState: String = SyncState.SYNCED,
) : Syncable
