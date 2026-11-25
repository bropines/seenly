package io.github.bropines.lens.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/** Where reading stopped in one folder. */
@Serializable
data class FolderProgress(
    /** The folder's identity: its file as text, like [GalleryItem.key]. */
    val key: String,
    val title: String,
    /** FileKit's serialised PlatformFile, to open the folder again. */
    val folder: String,
    /** The page stopped at, from 0. */
    val page: Int,
    val count: Int,
    /** The serialised PlatformFile of that page, for a cover. */
    val cover: String? = null,
    val updatedAt: Long = 0,
) {
    /** How far through the folder, 0..1; a single picture is "done". */
    val fraction: Float get() = if (count <= 1) 1f else (page.toFloat() / (count - 1)).coerceIn(0f, 1f)
}

/**
 * The folders read lately and the page each was left at, newest first, so a
 * chapter opens where it was closed. State, not a setting: it has a file of
 * its own and is not part of the settings export.
 */
class ReadingProgress(dataDir: String) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        produceFile = { "$dataDir/reading.preferences_pb".toPath() },
    )
    private val json = Json { ignoreUnknownKeys = true }

    val folders: Flow<List<FolderProgress>> = store.data.map { decode(it[FOLDERS]) }

    /** The page [key] was left at, or null for a folder not read before. */
    suspend fun position(key: String): Int? = folders.first().firstOrNull { it.key == key }?.page

    /** Puts [progress] first, replacing the folder's earlier entry; only the newest [MAX_FOLDERS] are kept. */
    suspend fun save(progress: FolderProgress) {
        store.edit { p ->
            val rest = decode(p[FOLDERS]).filter { it.key != progress.key }
            p[FOLDERS] = json.encodeToString(LIST, (listOf(progress) + rest).take(MAX_FOLDERS))
        }
    }

    suspend fun forget(key: String) {
        store.edit { p -> p[FOLDERS] = json.encodeToString(LIST, decode(p[FOLDERS]).filter { it.key != key }) }
    }

    private fun decode(text: String?): List<FolderProgress> =
        text?.let { runCatching { json.decodeFromString(LIST, it) }.getOrNull() }.orEmpty()

    private companion object {
        const val MAX_FOLDERS = 12
        val FOLDERS = stringPreferencesKey("folders")
        val LIST = ListSerializer(FolderProgress.serializer())
    }
}
