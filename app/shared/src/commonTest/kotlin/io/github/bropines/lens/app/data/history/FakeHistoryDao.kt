package io.github.bropines.lens.app.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** History's table in memory, for tests and screen renders, which cannot open the real database. */
class FakeHistoryDao(initial: List<HistoryEntry> = emptyList()) : HistoryDao {
    private val rows = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    override fun all(): Flow<List<HistoryEntry>> = rows.map { list -> list.sortedByDescending { it.createdAt } }

    override fun search(query: String): Flow<List<HistoryEntry>> = all().map { list ->
        list.filter { query in it.originalText || query in it.translatedText || query in it.title }
    }

    override fun recent(limit: Int): Flow<List<HistoryEntry>> = all().map { it.take(limit) }

    override fun keys(target: String): Flow<List<String>> =
        rows.map { list -> list.filter { it.targetLanguage == target }.map { it.imageKey } }

    override suspend fun find(key: String, target: String): HistoryEntry? =
        rows.value.firstOrNull { it.imageKey == key && it.targetLanguage == target }

    override suspend fun insert(entry: HistoryEntry): Long {
        val id = if (entry.id == 0L) nextId++ else entry.id
        // REPLACE: the row with this id, or with this picture and language, gives way.
        rows.value = rows.value.filterNot {
            it.id == id || (it.imageKey == entry.imageKey && it.targetLanguage == entry.targetLanguage)
        } + entry.copy(id = id)
        return id
    }

    override suspend fun byId(id: Long): HistoryEntry? = rows.value.firstOrNull { it.id == id }

    override suspend fun byKey(key: String): List<HistoryEntry> = rows.value.filter { it.imageKey == key }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun everything(): List<HistoryEntry> = rows.value

    override suspend fun clear() {
        rows.value = emptyList()
    }
}
