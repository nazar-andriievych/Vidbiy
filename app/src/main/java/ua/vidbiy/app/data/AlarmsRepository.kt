package ua.vidbiy.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.alarmsDataStore: DataStore<Preferences> by preferencesDataStore(name = "alarms")

/**
 * Сховище будильників. DataStore — це файл налаштувань із асинхронним доступом:
 * приблизно як IConfiguration, який ще й уміє віддавати зміни потоком (Flow ≈ IAsyncEnumerable).
 * Будильників одиниці, тож увесь список тримаємо одним JSON-рядком — база даних тут зайва.
 */
class AlarmsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("alarms_json")

    val alarms: Flow<List<Alarm>> = context.alarmsDataStore.data.map { prefs ->
        decode(prefs[key]).sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))
    }

    /** Додає новий будильник (id = 0) або оновлює наявний. Повертає збережений — уже з id. */
    suspend fun save(alarm: Alarm): Alarm {
        var saved = alarm
        edit { current ->
            if (alarm.id == Alarm.NEW_ID) {
                val nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1
                saved = alarm.copy(id = nextId)
                current + saved
            } else {
                current.map { if (it.id == alarm.id) alarm else it }
            }
        }
        return saved
    }

    suspend fun delete(id: Long) = edit { current -> current.filterNot { it.id == id } }

    suspend fun setEnabled(id: Long, enabled: Boolean) = edit { current ->
        current.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    /** Змінює всі будильники, що підходять під [predicate], однією транзакцією. */
    suspend fun updateWhere(predicate: (Alarm) -> Boolean, transform: (Alarm) -> Alarm) = edit { current ->
        current.map { if (predicate(it)) transform(it) else it }
    }

    private suspend fun edit(block: (List<Alarm>) -> List<Alarm>) {
        context.alarmsDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(block(decode(prefs[key])))
        }
    }

    private fun decode(raw: String?): List<Alarm> {
        if (raw.isNullOrBlank()) return emptyList()
        // Пошкоджений або несумісний JSON не має валити застосунок: краще порожній список,
        // ніж падіння на старті.
        return runCatching { json.decodeFromString<List<Alarm>>(raw) }.getOrDefault(emptyList())
    }
}
