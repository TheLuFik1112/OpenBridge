package app.openbridge.data

import android.content.Context
import app.openbridge.core.HistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class HistoryRepository(context: Context, json: Json) {
    private val store = JsonFile(
        context = context,
        fileName = "history.json",
        serializer = ListSerializer(HistoryEntry.serializer()),
        json = json,
        default = emptyList(),
    )

    private val _entries = MutableStateFlow(store.load())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    @Synchronized
    fun add(entry: HistoryEntry) {
        _entries.value = (listOf(entry) + _entries.value).take(300)
        store.save(_entries.value)
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        store.save(emptyList())
    }
}
