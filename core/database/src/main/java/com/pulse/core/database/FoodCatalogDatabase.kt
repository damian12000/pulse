package com.pulse.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.pulse.core.database.dao.FoodWithServings
import com.pulse.core.database.entity.FoodEntity
import com.pulse.core.database.entity.FoodFtsEntity
import com.pulse.core.database.entity.FoodServingEntity
import java.io.File

/**
 * The downloaded food catalog — 326,759 foods, read-only.
 *
 * **Why this is a separate database rather than prepopulating [PulseDatabase]:**
 *
 * Room can prepopulate from exactly one source, and there are two: the exercise
 * library (0.2 MB, ships in the APK so workouts work before any download) and
 * the food catalog (67 MB, downloaded on first run). Merging them would mean
 * the app has no database at all until a 67 MB download finishes.
 *
 * Keeping the catalog separate means the app is usable immediately and the
 * catalog arrives when it arrives. The cost is that catalog rows cannot satisfy
 * `diary_entry`'s foreign key — so a food is **copied into [PulseDatabase] the
 * first time it is used**, exactly as remotely-fetched foods already are. The
 * catalog is a searchable index; the app database holds what you have actually
 * touched.
 *
 * This also keeps the writable database small: 326k rows you never logged stay
 * out of backups and out of any future sync.
 */
@Database(
    entities = [FoodEntity::class, FoodServingEntity::class, FoodFtsEntity::class],
    version = 1,
    // Exported so tools/stamp_room_identity.py can stamp the downloaded asset
    // with THIS database's identity hash. It differs from PulseDatabase's —
    // a second Room database means a second hash, and stamping the wrong one
    // fails at open time with a confusing schema-mismatch error.
    exportSchema = true,
)
abstract class FoodCatalogDatabase : RoomDatabase() {

    abstract fun catalogDao(): FoodCatalogDao

    companion object {
        const val FILE_NAME = "opennutrition.db"

        /**
         * Opens the downloaded catalog, or null when it hasn't been downloaded.
         *
         * Read-only by contract, not just by convention: nothing writes to it,
         * and it is replaced wholesale when a newer dataset ships.
         */
        fun openIfPresent(context: Context, file: File): FoodCatalogDatabase? {
            if (!file.exists() || file.length() < MIN_PLAUSIBLE_BYTES) return null
            return Room.databaseBuilder(context, FoodCatalogDatabase::class.java, file.absolutePath)
                .createFromFile(file)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }

        /** Guards against a truncated or placeholder file being opened as a database. */
        private const val MIN_PLAUSIBLE_BYTES = 1_000_000L
    }
}

@Dao
interface FoodCatalogDao {

    @Transaction
    @Query(
        """
        SELECT * FROM food
        WHERE barcode = :barcode AND isDeleted = 0
        ORDER BY CASE dataConfidence WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END
        LIMIT 1
        """
    )
    suspend fun findByBarcode(barcode: String): FoodWithServings?

    @Transaction
    @Query(
        """
        SELECT f.* FROM food AS f
        JOIN food_fts ON food_fts.docid = f.rowid
        WHERE food_fts MATCH :query AND f.isDeleted = 0
        ORDER BY
            CASE f.foodType
                WHEN 'everyday' THEN 0
                WHEN 'prepared' THEN 1
                WHEN 'restaurant' THEN 2
                ELSE 3
            END,
            CASE f.dataConfidence WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,
            LENGTH(f.name)
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int): List<FoodWithServings>

    @Transaction
    @Query("SELECT * FROM food WHERE id = :id")
    suspend fun findById(id: String): FoodWithServings?

    @Query("SELECT COUNT(*) FROM food")
    suspend fun count(): Int
}
