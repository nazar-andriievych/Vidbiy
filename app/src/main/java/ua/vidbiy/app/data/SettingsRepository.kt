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

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Налаштування застосунку. Поки тут лише обраний регіон. */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val regionKey = stringPreferencesKey("selected_region")

    val selectedRegion: Flow<SelectedRegion?> = context.settingsDataStore.data.map { prefs ->
        prefs[regionKey]?.let { raw ->
            runCatching { json.decodeFromString<SelectedRegion>(raw) }.getOrNull()
        }
    }

    suspend fun setRegion(region: SelectedRegion) {
        context.settingsDataStore.edit { prefs ->
            prefs[regionKey] = json.encodeToString(region)
        }
    }
}
