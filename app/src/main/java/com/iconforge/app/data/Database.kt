package com.iconforge.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** A launchable activity on the device ("pkg/cls" is what icon packs map). */
@Entity(tableName = "installed_apps")
data class InstalledApp(
    @PrimaryKey val componentKey: String,
    val packageName: String,
    val activityName: String,
    val label: String,
    val isSystem: Boolean,
    val firstSeen: Long,
    /** Installed after the first scan and not yet looked at in the editor. */
    val isNew: Boolean = false,
)

@Entity(tableName = "icon_packs")
data class IconPackEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val author: String,
    val totalIcons: Int,
    val mappedComponents: Int,
    val versionCode: Long,
    val lastScanned: Long,
)

@Entity(tableName = "themes")
data class ThemeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Icon pack auto-matching uses for this theme (reset / new apps). */
    val basePack: String?,
    /** Random, fixed at creation: exported APK is com.iconforge.pack.<suffix>. */
    val packSuffix: String,
    val totalApps: Int = 0,
    val themedApps: Int = 0,
    val coverage: Float = 0f,
    val lastExportedAt: Long = 0,
    val exportCount: Int = 0,
    /** IconShape name: how adaptive icons are cut when exported/previewed. */
    val iconShape: String = "DEFAULT",
    /** Size of the icon inside [iconShape] (1 = as designed); ignored for the launcher default. */
    val iconScale: Float = 1f,
)

object Source {
    const val PACK = "PACK"          // icon from an installed icon pack
    const val ORIGINAL = "ORIGINAL"  // keep the app's own icon (on purpose)
    const val CUSTOM = "CUSTOM"      // image file (picked from gallery, or imported)
    const val GENERATED = "GENERATED" // app's own icon redrawn in a pack's style (iconPack = style source)
}

@Entity(tableName = "mappings", primaryKeys = ["themeId", "componentKey"], indices = [Index("packageName")])
data class MappingEntity(
    val themeId: Long,
    val componentKey: String,
    val packageName: String,
    val source: String,
    val iconPack: String? = null,
    val iconResource: String? = null,
    val customPath: String? = null,
    val isManual: Boolean,
    val lastUpdated: Long = System.currentTimeMillis(),
    /** Filled by Auto-fill (best guess / generated); shown under "To review" until accepted. */
    val isGuess: Boolean = false,
    /** Icon adjustments: size (1 = as is) and an optional background plate colour (ARGB). */
    val scale: Float = 1f,
    val bgColor: Int? = null,
)

@Dao
interface AppDao {
    @Query("SELECT * FROM installed_apps ORDER BY label COLLATE NOCASE")
    fun observe(): Flow<List<InstalledApp>>

    @Query("SELECT * FROM installed_apps")
    suspend fun all(): List<InstalledApp>

    @Upsert suspend fun upsert(apps: List<InstalledApp>)

    @Query("DELETE FROM installed_apps WHERE componentKey IN (:keys)")
    suspend fun delete(keys: List<String>)

    @Query("UPDATE installed_apps SET isNew = 0 WHERE componentKey IN (:keys)")
    suspend fun clearNew(keys: List<String>)

    @Query("UPDATE installed_apps SET isNew = 0")
    suspend fun clearAllNew()
}

@Dao
interface PackDao {
    @Query("SELECT * FROM icon_packs ORDER BY label COLLATE NOCASE")
    fun observe(): Flow<List<IconPackEntity>>

    @Query("SELECT * FROM icon_packs")
    suspend fun all(): List<IconPackEntity>

    @Upsert suspend fun upsert(packs: List<IconPackEntity>)

    @Query("DELETE FROM icon_packs WHERE packageName NOT IN (:keep)")
    suspend fun deleteOthers(keep: List<String>)
}

@Dao
interface ThemeDao {
    @Query("SELECT * FROM themes ORDER BY updatedAt DESC")
    fun observe(): Flow<List<ThemeEntity>>

    @Query("SELECT * FROM themes WHERE id = :id")
    fun observe(id: Long): Flow<ThemeEntity?>

    @Query("SELECT * FROM themes WHERE id = :id")
    suspend fun get(id: Long): ThemeEntity?

    @Query("SELECT * FROM themes")
    suspend fun all(): List<ThemeEntity>

    @Insert suspend fun insert(theme: ThemeEntity): Long
    @Update suspend fun update(theme: ThemeEntity)

    @Query("DELETE FROM themes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MappingDao {
    @Query("SELECT * FROM mappings WHERE themeId = :themeId")
    fun observe(themeId: Long): Flow<List<MappingEntity>>

    @Query("SELECT * FROM mappings WHERE themeId = :themeId")
    suspend fun forTheme(themeId: Long): List<MappingEntity>

    @Query("SELECT * FROM mappings WHERE themeId = :themeId AND componentKey IN (:keys)")
    suspend fun get(themeId: Long, keys: List<String>): List<MappingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(mappings: List<MappingEntity>)

    @Query("DELETE FROM mappings WHERE themeId = :themeId AND componentKey IN (:keys)")
    suspend fun delete(themeId: Long, keys: List<String>)

    @Query("DELETE FROM mappings WHERE themeId = :themeId")
    suspend fun deleteTheme(themeId: Long)

    @Transaction
    suspend fun replace(themeId: Long, remove: List<String>, put: List<MappingEntity>) {
        if (remove.isNotEmpty()) delete(themeId, remove)
        if (put.isNotEmpty()) put(put)
    }
}

@Database(
    entities = [InstalledApp::class, IconPackEntity::class, ThemeEntity::class, MappingEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun apps(): AppDao
    abstract fun packs(): PackDao
    abstract fun themes(): ThemeDao
    abstract fun mappings(): MappingDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "iconforge.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE themes ADD COLUMN iconScale REAL NOT NULL DEFAULT 1.0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mappings ADD COLUMN isGuess INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mappings ADD COLUMN scale REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE mappings ADD COLUMN bgColor INTEGER")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE themes ADD COLUMN iconShape TEXT NOT NULL DEFAULT 'DEFAULT'")
            }
        }
    }
}
