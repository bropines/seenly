package io.github.bropines.lens.app.data.history

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import kotlinx.coroutines.flow.Flow

/**
 * One scanned image. The picture and the full Lens result live in files next
 * to the database (they are large); the row keeps what the list shows and
 * what search looks through.
 */
@Entity(
    tableName = "history",
    indices = [Index("createdAt"), Index(value = ["imageKey", "targetLanguage"], unique = true)],
)
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Where the image came from, so re-scanning the same picture updates its row. */
    val imageKey: String,
    val title: String,
    /** Our own copy: a shared content:// grant does not survive the process. */
    val imagePath: String,
    val resultPath: String,
    val sourceLanguage: String?,
    val targetLanguage: String,
    val originalText: String,
    val translatedText: String,
    val createdAt: Long,
    /**
     * What was asked of the scanner (source language, recognition hint, Lens or
     * the device). A page asked for differently is a different scan; rows from
     * before version 2 have none and are never taken for one.
     */
    @ColumnInfo(defaultValue = "") val scanSpec: String = "",
    /** Clockwise quarter turns the picture was read at. */
    @ColumnInfo(defaultValue = "0") val rotation: Int = 0,
    /** The scanned file's length and a hash of it: the same name with other pixels is another picture. */
    @ColumnInfo(defaultValue = "0") val contentSize: Long = 0,
    @ColumnInfo(defaultValue = "0") val contentHash: Long = 0,
    /** The folder the page was read from, when it was one: history shows a chapter as one stack. */
    val folderKey: String? = null,
    val folderTitle: String? = null,
    @ColumnInfo(defaultValue = "0") val pageIndex: Int = 0,
    /** The picture's shape, for laying a thumbnail out before it is decoded; 0 when unknown. */
    @ColumnInfo(defaultValue = "0") val imageWidth: Int = 0,
    @ColumnInfo(defaultValue = "0") val imageHeight: Int = 0,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY createdAt DESC")
    fun all(): Flow<List<HistoryEntry>>

    @Query(
        "SELECT * FROM history WHERE originalText LIKE '%' || :query || '%' " +
            "OR translatedText LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' ORDER BY createdAt DESC",
    )
    fun search(query: String): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<HistoryEntry>>

    /** The pictures that have a translation into [target], by where they came from. */
    @Query("SELECT imageKey FROM history WHERE targetLanguage = :target")
    fun keys(target: String): Flow<List<String>>

    @Query("SELECT * FROM history WHERE imageKey = :key AND targetLanguage = :target LIMIT 1")
    suspend fun find(key: String, target: String): HistoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry): Long

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun byId(id: Long): HistoryEntry?

    /** Every language's entry for one picture. */
    @Query("SELECT * FROM history WHERE imageKey = :key")
    suspend fun byKey(key: String): List<HistoryEntry>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM history")
    suspend fun everything(): List<HistoryEntry>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(
    entities = [HistoryEntry::class],
    version = 2,
    // 2: what a row was scanned with and from where, so a page read before opens without a request.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@ConstructedBy(LensDatabaseConstructor::class)
abstract class LensDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
}

// Room's compiler generates the actual implementations.
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object LensDatabaseConstructor : RoomDatabaseConstructor<LensDatabase> {
    override fun initialize(): LensDatabase
}
