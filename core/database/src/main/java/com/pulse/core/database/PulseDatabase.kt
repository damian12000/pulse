package com.pulse.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.pulse.core.database.dao.DiaryDao
import com.pulse.core.database.dao.ExerciseDao
import com.pulse.core.database.dao.FoodDao
import com.pulse.core.database.dao.GoalDao
import com.pulse.core.database.dao.MeasurementDao
import com.pulse.core.database.dao.PendingLookupDao
import com.pulse.core.database.dao.PersonalRecordDao
import com.pulse.core.database.dao.ProfileDao
import com.pulse.core.database.dao.RecipeDao
import com.pulse.core.database.dao.SavedMealDao
import com.pulse.core.database.dao.TemplateDao
import com.pulse.core.database.dao.WaterDao
import com.pulse.core.database.dao.WorkoutDao
import com.pulse.core.database.entity.BodyMeasurementEntity
import com.pulse.core.database.entity.DiaryEntryEntity
import com.pulse.core.database.entity.ExerciseEntity
import com.pulse.core.database.entity.ExerciseFtsEntity
import com.pulse.core.database.entity.FoodEntity
import com.pulse.core.database.entity.FoodFtsEntity
import com.pulse.core.database.entity.FoodServingEntity
import com.pulse.core.database.entity.FoodUsageEntity
import com.pulse.core.database.entity.GoalTargetEntity
import com.pulse.core.database.entity.PendingLookupEntity
import com.pulse.core.database.entity.PersonalRecordEntity
import com.pulse.core.database.entity.ProfileEntity
import com.pulse.core.database.entity.RecipeEntity
import com.pulse.core.database.entity.RecipeIngredientEntity
import com.pulse.core.database.entity.SavedMealEntity
import com.pulse.core.database.entity.SavedMealItemEntity
import com.pulse.core.database.entity.TemplateExerciseEntity
import com.pulse.core.database.entity.TemplateSetEntity
import com.pulse.core.database.entity.WaterEntryEntity
import com.pulse.core.database.entity.WorkoutEntity
import com.pulse.core.database.entity.WorkoutExerciseEntity
import com.pulse.core.database.entity.WorkoutSetEntity
import com.pulse.core.database.entity.WorkoutTemplateEntity

@Database(
    entities = [
        // food
        FoodEntity::class,
        FoodServingEntity::class,
        FoodUsageEntity::class,
        FoodFtsEntity::class,
        // diary
        DiaryEntryEntity::class,
        WaterEntryEntity::class,
        SavedMealEntity::class,
        SavedMealItemEntity::class,
        RecipeEntity::class,
        RecipeIngredientEntity::class,
        // workout
        ExerciseEntity::class,
        ExerciseFtsEntity::class,
        WorkoutTemplateEntity::class,
        TemplateExerciseEntity::class,
        TemplateSetEntity::class,
        WorkoutEntity::class,
        WorkoutExerciseEntity::class,
        WorkoutSetEntity::class,
        PersonalRecordEntity::class,
        // profile / progress
        ProfileEntity::class,
        GoalTargetEntity::class,
        BodyMeasurementEntity::class,
        PendingLookupEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PulseDatabase : RoomDatabase() {

    abstract fun foodDao(): FoodDao
    abstract fun diaryDao(): DiaryDao
    abstract fun waterDao(): WaterDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun templateDao(): TemplateDao
    abstract fun personalRecordDao(): PersonalRecordDao
    abstract fun profileDao(): ProfileDao
    abstract fun goalDao(): GoalDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun savedMealDao(): SavedMealDao
    abstract fun recipeDao(): RecipeDao
    abstract fun pendingLookupDao(): PendingLookupDao

    companion object {
        const val NAME = "pulse.db"

        /**
         * Builds the database.
         *
         * **No `fallbackToDestructiveMigration()`, ever** — this database holds
         * irreplaceable history. Every schema change ships an explicit
         * [androidx.room.migration.Migration] with a `MigrationTestHelper` test.
         *
         * Foreign keys are enabled explicitly: SQLite leaves them off by
         * default, and without them the `ON DELETE CASCADE` declarations on
         * servings, sets and ingredients would silently do nothing.
         */
        fun build(context: Context): PulseDatabase =
            Room.databaseBuilder(context, PulseDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
