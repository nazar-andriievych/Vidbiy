package ua.vidbiy.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ua.vidbiy.app.BuildConfig

/**
 * Очікування відбою, яке триває просто зараз. Зберігається на диск, бо перезавантаження
 * стирає і службу очікування, і зареєстрований крайній час — а будильник має пережити це.
 */
@Serializable
data class PendingWait(val alarmId: Long, val deadlineMillis: Long)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Налаштування застосунку. Поки тут лише обраний регіон. */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val regionKey = stringPreferencesKey("selected_region")
    private val proxyUrlKey = stringPreferencesKey("debug_proxy_url")
    private val pendingWaitKey = stringPreferencesKey("pending_wait")

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

    /**
     * Адреса проксі. У релізі це завжди одна константа; debug-збірка дозволяє вказати
     * локальний воркер (`npm run dev -- --ip 0.0.0.0`), щоб ганяти тривогу й відбій вручну.
     */
    val debugProxyUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[proxyUrlKey].orEmpty()
    }

    suspend fun proxyBaseUrl(): String {
        if (!BuildConfig.DEBUG) return ProxyConfig.BASE_URL
        return debugProxyUrl.first().trim().trimEnd('/').ifEmpty { ProxyConfig.BASE_URL }
    }

    suspend fun setDebugProxyUrl(url: String) {
        context.settingsDataStore.edit { prefs -> prefs[proxyUrlKey] = url }
    }

    suspend fun pendingWait(): PendingWait? {
        val raw = context.settingsDataStore.data.first()[pendingWaitKey] ?: return null
        return runCatching { json.decodeFromString<PendingWait>(raw) }.getOrNull()
    }

    suspend fun setPendingWait(wait: PendingWait) {
        context.settingsDataStore.edit { prefs -> prefs[pendingWaitKey] = json.encodeToString(wait) }
    }

    suspend fun clearPendingWait() {
        context.settingsDataStore.edit { prefs -> prefs.remove(pendingWaitKey) }
    }
}
