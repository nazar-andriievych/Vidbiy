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
import ua.vidbiy.app.ui.theme.ThemeMode

/**
 * Очікування відбою, яке триває просто зараз. Зберігається на диск, бо перезавантаження
 * стирає і службу очікування, і зареєстрований крайній час — а будильник має пережити це.
 */
@Serializable
data class PendingWait(
    val alarmId: Long,
    /** Крайній час (FR-6); null — не заданий. */
    val deadlineMillis: Long? = null,
    /** Коли почалося очікування. 0 — запис зі старої версії, де цього поля не було. */
    val startedAtMillis: Long = 0L,
) {
    /**
     * Коли будильник здасться за будь-яких умов: крайній час або доба очікування.
     * Доба випливає з FR-17 — тривога, довша за добу, не рахується, — і страхує очікування
     * без крайнього часу: без неї служба, яку прибила система, не мала б чим задзвонити.
     */
    fun giveUpAtMillis(): Long {
        val started = startedAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val backstop = started + MAX_WAIT_MILLIS
        return deadlineMillis?.coerceAtMost(backstop) ?: backstop
    }

    companion object {
        const val MAX_WAIT_MILLIS = 24 * 60 * 60 * 1000L
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Налаштування застосунку: тема, стан очікування, адреса проксі для розробки. */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val regionKey = stringPreferencesKey("selected_region")
    private val proxyUrlKey = stringPreferencesKey("debug_proxy_url")
    private val pendingWaitKey = stringPreferencesKey("pending_wait")
    private val themeModeKey = stringPreferencesKey("theme_mode")

    /** FR-32: тема застосунку; за замовчуванням — як у системі. */
    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        ThemeMode.entries.firstOrNull { it.name == prefs[themeModeKey] } ?: ThemeMode.System
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { prefs -> prefs[themeModeKey] = mode.name }
    }

    /**
     * Регіон з версій до «Моїх місць», один на весь застосунок. Лише читається під час
     * перенесення ([LegacyMigration]) і після нього стирається.
     */
    suspend fun legacyRegion(): SelectedRegion? = context.settingsDataStore.data.first()[regionKey]?.let { raw ->
        runCatching { json.decodeFromString<SelectedRegion>(raw) }.getOrNull()
    }

    suspend fun clearLegacyRegion() {
        context.settingsDataStore.edit { prefs -> prefs.remove(regionKey) }
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

    /**
     * Очікування, яке триває просто зараз. Потоком — щоб список будильників показував
     * «чекає відбою» рівно доти, доки служба справді чекає, і сам гасив напис,
     * коли вона зупинилася.
     */
    val pendingWait: Flow<PendingWait?> = context.settingsDataStore.data.map { prefs ->
        prefs[pendingWaitKey]?.let { raw ->
            runCatching { json.decodeFromString<PendingWait>(raw) }.getOrNull()
        }
    }

    suspend fun currentPendingWait(): PendingWait? = pendingWait.first()

    suspend fun setPendingWait(wait: PendingWait) {
        context.settingsDataStore.edit { prefs -> prefs[pendingWaitKey] = json.encodeToString(wait) }
    }

    suspend fun clearPendingWait() {
        context.settingsDataStore.edit { prefs -> prefs.remove(pendingWaitKey) }
    }
}
