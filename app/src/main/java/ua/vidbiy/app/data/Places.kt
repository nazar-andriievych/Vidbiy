package ua.vidbiy.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** FR-26: збережений регіон з назвою від користувача («Дім», «Дача»). */
@Serializable
data class Place(
    val id: Long,
    val name: String,
    val region: SelectedRegion,
) {
    companion object {
        /** FR-26a: назва — до 24 символів. */
        const val MAX_NAME_LENGTH = 24
    }
}

/** Усі місця разом з тим, яке з них основне. */
data class PlacesState(
    val places: List<Place> = emptyList(),
    val primaryId: Long? = null,
) {
    val primary: Place? get() = places.firstOrNull { it.id == primaryId } ?: places.firstOrNull()

    fun byId(id: Long?): Place? = id?.let { wanted -> places.firstOrNull { it.id == wanted } }

    /** Основне місце — першим, решта в порядку додавання. */
    val ordered: List<Place> get() = places.sortedBy { if (it.id == primary?.id) 0 else 1 }
}

private val Context.placesDataStore: DataStore<Preferences> by preferencesDataStore(name = "places")

/**
 * «Мої місця». Живуть лише на телефоні (FR-26, NFR-4). Як і будильники, увесь список —
 * один JSON-рядок: місць одиниці.
 *
 * Будильник тримає власну копію регіону (див. [Alarm.region]), тому зміну регіону
 * й видалення місця треба віддзеркалити в будильниках — це робить [PlacesEditor].
 */
class PlacesRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val placesKey = stringPreferencesKey("places_json")
    private val primaryKey = longPreferencesKey("primary_place_id")

    val state: Flow<PlacesState> = context.placesDataStore.data.map { prefs ->
        PlacesState(places = decode(prefs[placesKey]), primaryId = prefs[primaryKey])
    }

    suspend fun current(): PlacesState = state.first()

    /** Додає місце; перше стає основним (FR-26a). Повертає збережене — уже з id. */
    suspend fun add(name: String, region: SelectedRegion): Place {
        lateinit var added: Place
        context.placesDataStore.edit { prefs ->
            val places = decode(prefs[placesKey])
            added = Place(id = (places.maxOfOrNull { it.id } ?: 0L) + 1, name = name.clean(), region = region)
            prefs[placesKey] = json.encodeToString(places + added)
            if (places.isEmpty()) prefs[primaryKey] = added.id
        }
        return added
    }

    suspend fun rename(id: Long, name: String) = update(id) { it.copy(name = name.clean()) }

    suspend fun changeRegion(id: Long, region: SelectedRegion) = update(id) { it.copy(region = region) }

    suspend fun setPrimary(id: Long) {
        context.placesDataStore.edit { prefs -> prefs[primaryKey] = id }
    }

    /** Основне місце не видаляється, доки основним не стане інше (FR-26a). */
    suspend fun delete(id: Long): Boolean {
        var deleted = false
        context.placesDataStore.edit { prefs ->
            val places = decode(prefs[placesKey])
            val primaryId = prefs[primaryKey] ?: places.firstOrNull()?.id
            if (id == primaryId) return@edit
            prefs[placesKey] = json.encodeToString(places.filterNot { it.id == id })
            deleted = true
        }
        return deleted
    }

    private suspend fun update(id: Long, transform: (Place) -> Place) {
        context.placesDataStore.edit { prefs ->
            val places = decode(prefs[placesKey]).map { if (it.id == id) transform(it) else it }
            prefs[placesKey] = json.encodeToString(places)
        }
    }

    private fun String.clean() = trim().take(Place.MAX_NAME_LENGTH)

    private fun decode(raw: String?): List<Place> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Place>>(raw) }.getOrDefault(emptyList())
    }
}

/**
 * Зміни місць, що зачіпають будильники (FR-26a):
 * - нова область/громада місця → будильники з цим місцем чекають на тривоги там;
 * - видалене місце → будильники зберігають регіон, лише без назви.
 */
class PlacesEditor(
    private val places: PlacesRepository,
    private val alarms: AlarmsRepository,
) {
    suspend fun changeRegion(placeId: Long, region: SelectedRegion) {
        places.changeRegion(placeId, region)
        alarms.updateWhere({ it.placeId == placeId }) { it.copy(region = region) }
    }

    suspend fun delete(placeId: Long) {
        if (places.delete(placeId)) {
            alarms.updateWhere({ it.placeId == placeId }) { it.copy(placeId = null) }
        }
    }
}
