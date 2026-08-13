package com.pulse.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.pulse.core.database.PulseDatabase
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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * The exercise library ships inside the APK so a fresh install is fully
     * usable for workouts before any network access — the food databases are
     * far too large for that and download on first run instead
     * (PHASE2_ARCHITECTURE.md §4.8).
     */
    private const val EXERCISE_ASSET = "databases/exercises.db"

    @Provides
    @Singleton
    fun providePulseDatabase(@ApplicationContext context: Context): PulseDatabase {
        val builder = Room.databaseBuilder(context, PulseDatabase::class.java, PulseDatabase.NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)

        // Prepopulate from the bundled exercise library when it's present.
        // Absence must not be fatal: Room then creates empty tables and the app
        // still runs, just with no seeded exercises.
        if (context.hasAsset(EXERCISE_ASSET)) {
            builder.createFromAsset(EXERCISE_ASSET)
        }

        // NOTE: deliberately no fallbackToDestructiveMigration(). This database
        // holds irreplaceable history; a schema change without a written
        // migration must fail loudly in development, not silently wipe data.
        return builder.build()
    }

    @Provides fun provideFoodDao(db: PulseDatabase): FoodDao = db.foodDao()
    @Provides fun provideDiaryDao(db: PulseDatabase): DiaryDao = db.diaryDao()
    @Provides fun provideWaterDao(db: PulseDatabase): WaterDao = db.waterDao()
    @Provides fun provideExerciseDao(db: PulseDatabase): ExerciseDao = db.exerciseDao()
    @Provides fun provideWorkoutDao(db: PulseDatabase): WorkoutDao = db.workoutDao()
    @Provides fun provideTemplateDao(db: PulseDatabase): TemplateDao = db.templateDao()
    @Provides fun providePersonalRecordDao(db: PulseDatabase): PersonalRecordDao = db.personalRecordDao()
    @Provides fun provideProfileDao(db: PulseDatabase): ProfileDao = db.profileDao()
    @Provides fun provideGoalDao(db: PulseDatabase): GoalDao = db.goalDao()
    @Provides fun provideMeasurementDao(db: PulseDatabase): MeasurementDao = db.measurementDao()
    @Provides fun provideSavedMealDao(db: PulseDatabase): SavedMealDao = db.savedMealDao()
    @Provides fun provideRecipeDao(db: PulseDatabase): RecipeDao = db.recipeDao()
    @Provides fun providePendingLookupDao(db: PulseDatabase): PendingLookupDao = db.pendingLookupDao()
}

private fun Context.hasAsset(path: String): Boolean = runCatching {
    assets.open(path).close()
    true
}.getOrDefault(false)

/**
 * Where downloaded database assets live.
 *
 * The food databases (~197 MB) are fetched on first run rather than shipped in
 * the APK, so this is the agreed location between the downloader and whatever
 * attaches them.
 */
object BundledAssets {
    const val FOOD_DB = "opennutrition.db"
    const val EXERCISE_DB = "exercises.db"

    fun downloadDir(context: Context): File =
        File(context.filesDir, "databases").apply { mkdirs() }

    fun foodDatabase(context: Context): File = File(downloadDir(context), FOOD_DB)

    fun isFoodDatabaseReady(context: Context): Boolean =
        foodDatabase(context).let { it.exists() && it.length() > 1_000_000 }
}
