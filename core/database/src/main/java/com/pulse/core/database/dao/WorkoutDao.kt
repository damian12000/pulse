package com.pulse.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.pulse.core.database.entity.ExerciseEntity
import com.pulse.core.database.entity.PersonalRecordEntity
import com.pulse.core.database.entity.TemplateExerciseEntity
import com.pulse.core.database.entity.TemplateSetEntity
import com.pulse.core.database.entity.WorkoutEntity
import com.pulse.core.database.entity.WorkoutExerciseEntity
import com.pulse.core.database.entity.WorkoutSetEntity
import com.pulse.core.database.entity.WorkoutTemplateEntity
import kotlinx.coroutines.flow.Flow

data class WorkoutExerciseWithSets(
    @Embedded val exercise: WorkoutExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "workoutExerciseId")
    val sets: List<WorkoutSetEntity>,
)

data class FullWorkout(
    @Embedded val workout: WorkoutEntity,
    @Relation(
        entity = WorkoutExerciseEntity::class,
        parentColumn = "id",
        entityColumn = "workoutId",
    )
    val exercises: List<WorkoutExerciseWithSets>,
)

data class TemplateExerciseWithSets(
    @Embedded val templateExercise: TemplateExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "templateExerciseId")
    val sets: List<TemplateSetEntity>,
)

data class FullTemplate(
    @Embedded val template: WorkoutTemplateEntity,
    @Relation(
        entity = TemplateExerciseEntity::class,
        parentColumn = "id",
        entityColumn = "templateId",
    )
    val exercises: List<TemplateExerciseWithSets>,
)

@Dao
interface ExerciseDao {

    @Query("SELECT * FROM exercise WHERE isDeleted = 0 ORDER BY name")
    fun observeAll(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercise WHERE id = :id")
    suspend fun findById(id: String): ExerciseEntity?

    @Query(
        """
        SELECT * FROM exercise
        WHERE isDeleted = 0
          AND (:muscle IS NULL OR primaryMuscle = :muscle)
          AND (:equipment IS NULL OR equipment = :equipment)
          AND (:category IS NULL OR category = :category)
        ORDER BY name
        """
    )
    fun observeFiltered(
        muscle: String? = null,
        equipment: String? = null,
        category: String? = null,
    ): Flow<List<ExerciseEntity>>

    @Query(
        """
        SELECT e.* FROM exercise AS e
        JOIN exercise_fts ON exercise_fts.docid = e.rowid
        WHERE exercise_fts MATCH :query AND e.isDeleted = 0
        ORDER BY CASE WHEN e.isUserCreated THEN 0 ELSE 1 END, LENGTH(e.name)
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int = 50): List<ExerciseEntity>

    @Upsert
    suspend fun upsert(exercise: ExerciseEntity)

    @Upsert
    suspend fun upsertAll(exercises: List<ExerciseEntity>)

    @Query("SELECT COUNT(*) FROM exercise WHERE isDeleted = 0")
    suspend fun count(): Int

    /** Guards the seed: every exercise must carry a valid tracking mode. */
    @Query("SELECT COUNT(*) FROM exercise WHERE trackingMode NOT IN (:valid)")
    suspend fun countInvalidTrackingModes(valid: Set<String>): Int
}

@Dao
interface WorkoutDao {

    /** `finishedAt IS NULL` is the in-progress session — survives process death. */
    @Transaction
    @Query("SELECT * FROM workout WHERE finishedAt IS NULL AND isDeleted = 0 ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<FullWorkout?>

    @Transaction
    @Query("SELECT * FROM workout WHERE id = :id")
    fun observeFull(id: String): Flow<FullWorkout?>

    @Transaction
    @Query(
        """
        SELECT * FROM workout
        WHERE finishedAt IS NOT NULL AND isDeleted = 0
        ORDER BY startedAt DESC LIMIT :limit OFFSET :offset
        """
    )
    fun observeHistory(limit: Int = 30, offset: Int = 0): Flow<List<FullWorkout>>

    @Query("SELECT * FROM workout WHERE id = :id")
    suspend fun findById(id: String): WorkoutEntity?

    @Upsert suspend fun upsert(workout: WorkoutEntity)
    @Upsert suspend fun upsertExercise(exercise: WorkoutExerciseEntity)
    @Upsert suspend fun upsertExercises(exercises: List<WorkoutExerciseEntity>)
    @Upsert suspend fun upsertSet(set: WorkoutSetEntity)
    @Upsert suspend fun upsertSets(sets: List<WorkoutSetEntity>)

    @Query("DELETE FROM workout_set WHERE id = :id")
    suspend fun deleteSet(id: String)

    @Query("UPDATE workout SET isDeleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    /**
     * Previous performance for an exercise — shown inline while logging so you
     * can beat last time without leaving the screen.
     */
    @Query(
        """
        SELECT s.* FROM workout_set AS s
        JOIN workout_exercise AS we ON we.id = s.workoutExerciseId
        JOIN workout AS w ON w.id = we.workoutId
        WHERE we.exerciseId = :exerciseId
          AND w.finishedAt IS NOT NULL
          AND w.isDeleted = 0
          AND s.isCompleted = 1
        ORDER BY w.startedAt DESC, s.setIndex
        LIMIT :limit
        """
    )
    suspend fun lastPerformance(exerciseId: String, limit: Int = 10): List<WorkoutSetEntity>

    @Query("SELECT COUNT(*) FROM workout WHERE finishedAt IS NOT NULL AND isDeleted = 0")
    suspend fun completedCount(): Int
}

@Dao
interface TemplateDao {

    @Transaction
    @Query("SELECT * FROM workout_template WHERE isDeleted = 0 ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<FullTemplate>>

    @Transaction
    @Query("SELECT * FROM workout_template WHERE id = :id")
    fun observeFull(id: String): Flow<FullTemplate?>

    @Upsert suspend fun upsert(template: WorkoutTemplateEntity)
    @Upsert suspend fun upsertExercises(exercises: List<TemplateExerciseEntity>)
    @Upsert suspend fun upsertSets(sets: List<TemplateSetEntity>)

    @Query("UPDATE workout_template SET isDeleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    @Query("UPDATE workout_template SET lastPerformedAt = :at WHERE id = :id")
    suspend fun markPerformed(id: String, at: Long)
}

@Dao
interface PersonalRecordDao {

    @Query("SELECT * FROM personal_record WHERE isDeleted = 0 ORDER BY achievedAt DESC")
    fun observeAll(): Flow<List<PersonalRecordEntity>>

    @Query(
        """
        SELECT * FROM personal_record
        WHERE exerciseId = :exerciseId AND recordType = :type AND isDeleted = 0
        ORDER BY value DESC LIMIT 1
        """
    )
    suspend fun best(exerciseId: String, type: String): PersonalRecordEntity?

    @Query("SELECT * FROM personal_record WHERE exerciseId = :exerciseId AND isDeleted = 0")
    fun observeForExercise(exerciseId: String): Flow<List<PersonalRecordEntity>>

    @Upsert suspend fun upsert(record: PersonalRecordEntity)
}
