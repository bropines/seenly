package io.github.bropines.lens.app.data.history

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

    @Query("SELECT * FROM history WHERE imageKey = :key AND targetLanguage = :target LIMIT 1")
    suspend fun find(key: String, target: String): HistoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry): Long

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun byId(id: Long): HistoryEntry?

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM history")
    suspend fun everything(): List<HistoryEntry>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(entities = [HistoryEntry::class], version = 1)
@ConstructedBy(LensDatabaseConstructor::class)
abstract class LensDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
}

// Room's compiler generates the actual implementations.
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object LensDatabaseConstructor : RoomDatabaseConstructor<LensDatabase> {
    override fun initialize(): LensDatabase
}
