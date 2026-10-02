package ua.vidbiy.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import ua.vidbiy.app.alarm.nextTriggerAt
import java.time.LocalDateTime

private val Context.alarmsDataStore: DataStore<Preferences> by preferencesDataStore(name = "alarms")

/**
 * Сховище будильників. DataStore — це файл налаштувань із асинхронним доступом:
 * приблизно як IConfiguration, який ще й уміє віддавати зміни потоком (Flow ≈ IAsyncEnumerable).
 * Будильників одиниці, тож увесь список тримаємо одним JSON-рядком — база даних тут зайва.
 */
class AlarmsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("alarms_json")

    /** Частину будильників викинуто як нечитабельні, а користувач цього ще не бачив (див. [unreadable]). */
    private val noticeKey = booleanPreferencesKey("alarms_unreadable_notice")

    val alarms: Flow<List<Alarm>> = context.alarmsDataStore.data.map { prefs ->
        json.decodeListLenient<Alarm>(prefs[key]).items.sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))
    }

    /**
     * Банер «Не вдалося прочитати будильники». Горить, поки на диску лежить нечитабельне
     * або поки викинуте не «прийняте» користувачем: він додав чи видалив будильник
     * ([save] нового, [delete]). Автоматичні зміни (старт застосунку, спрацювання) його не гасять —
     * інакше банер зник би раніше, ніж людина встигла його побачити.
     */
    val unreadable: Flow<Boolean> = context.alarmsDataStore.data.map { prefs ->
        prefs[noticeKey] == true || json.decodeListLenient<Alarm>(prefs[key]).dropped > 0
    }

    /** Додає новий будильник (id = 0) або оновлює наявний. Повертає збережений — уже з id. */
    suspend fun save(alarm: Alarm): Alarm {
        var saved = alarm
        edit(acknowledgesLoss = alarm.id == Alarm.NEW_ID) { current ->
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

    suspend fun delete(id: Long) = edit(acknowledgesLoss = true) { current -> current.filterNot { it.id == id } }

    /**
     * Вмикає або вимикає будильник. Дата, що вже минула, при цьому зникає: увімкнений
     * знову одноразовий будильник спрацює найближчого разу, а не «ніколи».
     */
    suspend fun setEnabled(id: Long, enabled: Boolean, now: LocalDateTime = LocalDateTime.now()) = edit { current ->
        current.map { if (it.id == id) it.copy(enabled = enabled).withoutPastDate(now) else it }
    }

    /**
     * Будильник з датою, яку пропущено (телефон був вимкнений: тоді Android не будить
     * сторонні застосунки), вимикається без дзвінка. Повертає список уже після цього —
     * саме його й треба ставити в розклад.
     */
    suspend fun disableMissed(now: LocalDateTime = LocalDateTime.now()): List<Alarm> {
        edit { current ->
            // Вимкнений користувачем будильник теж забуває минулу дату — щоб картка її не показувала.
            current.map { if (it.date != null && it.nextTriggerAt(now) == null) it.copy(enabled = false, date = null) else it }
        }
        return alarms.first()
    }

    /** Змінює всі будильники, що підходять під [predicate], однією транзакцією. */
    suspend fun updateWhere(predicate: (Alarm) -> Boolean, transform: (Alarm) -> Alarm) = edit { current ->
        current.map { if (predicate(it)) transform(it) else it }
    }

    /**
     * Читає список по одному елементу. Нечитабельне викидається назавжди (без резервної копії:
     * будильник легко створити знову, а звернутися по відновлення нікуди) і лишає прапорець
     * для банера. Застосунок не застрягає з незмінним списком. [acknowledgesLoss] — користувач
     * сам додав чи видалив будильник: банер про втрату більше не потрібен.
     */
    private suspend fun edit(acknowledgesLoss: Boolean = false, block: (List<Alarm>) -> List<Alarm>) {
        context.alarmsDataStore.edit { prefs ->
            val read = json.decodeListLenient<Alarm>(prefs[key])
            if (read.dropped > 0) {
                Log.e("VidbiyAlarms", "Викинуто нечитабельних будильників: ${read.dropped}")
                prefs[noticeKey] = true
            }
            if (acknowledgesLoss) prefs[noticeKey] = false
            prefs[key] = json.encodeToString(block(read.items))
        }
    }
}

/** Дата, що вже минула, нічого не означає — лишається одноразовий будильник без дати. */
fun Alarm.withoutPastDate(now: LocalDateTime): Alarm =
    if (date != null && nextTriggerAt(now) == null) copy(date = null) else this
