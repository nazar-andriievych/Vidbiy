package ua.vidbiy.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

/**
 * Що зараз бачить служба очікування — для екрана очікування, банера й сповіщення.
 * Служба перезаписує його після кожної перевірки.
 */
@Serializable
data class WaitStatus(
    val alarmId: Long,
    /** Найвищий рівень, на який чекаємо; null — ще перевіряємо або триває пауза після відбою. */
    val level: AlertLevel? = null,
    /** Текст причини від ukrainealarm, якщо є (FR-28: лише для показу). */
    val reason: String? = null,
    /** Коли сервер востаннє підтвердив дані, за годинником телефона. */
    val confirmedAtMillis: Long? = null,
    /** Відбій під час очікування — почалася пауза (FR-14). */
    val allClearAtMillis: Long? = null,
    /** Коли задзвонить, якщо тривога не повернеться. */
    val ringAtMillis: Long? = null,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Налаштування застосунку: тема, стан очікування, адреса проксі для розробки. */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val regionKey = stringPreferencesKey("selected_region")
    private val proxyUrlKey = stringPreferencesKey("debug_proxy_url")
    private val pendingWaitKey = stringPreferencesKey("pending_wait")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val waitStatusKey = stringPreferencesKey("wait_status")
    private val snoozeMinutesKey = intPreferencesKey("snooze_minutes")
    private val oneShotWaitForKey = stringPreferencesKey("one_shot_wait_for")
    private val oneShotPauseKey = intPreferencesKey("one_shot_pause_minutes")

    /** FR-22: рівень для разового режиму, задається один раз у налаштуваннях. */
    val oneShotWaitFor: Flow<WaitFor> = context.settingsDataStore.data.map { prefs ->
        WaitFor.entries.firstOrNull { it.name == prefs[oneShotWaitForKey] } ?: WaitFor.RED_AND_YELLOW
    }

    suspend fun setOneShotWaitFor(waitFor: WaitFor) {
        context.settingsDataStore.edit { prefs -> prefs[oneShotWaitForKey] = waitFor.name }
    }

    /** FR-22: пауза після відбою для разового режиму; як і в будильника, за замовчуванням 0 (FR-5). */
    val oneShotPauseMinutes: Flow<Int> = context.settingsDataStore.data.map { prefs -> prefs[oneShotPauseKey] ?: 0 }

    suspend fun setOneShotPauseMinutes(minutes: Int) {
        context.settingsDataStore.edit { prefs -> prefs[oneShotPauseKey] = minutes }
    }

    /** FR-19: тривалість відкладення, одна на весь застосунок. */
    val snoozeMinutes: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[snoozeMinutesKey] ?: DEFAULT_SNOOZE_MINUTES
    }

    suspend fun setSnoozeMinutes(minutes: Int) {
        context.settingsDataStore.edit { prefs -> prefs[snoozeMinutesKey] = minutes }
    }

    val waitStatus: Flow<WaitStatus?> = context.settingsDataStore.data.map { prefs ->
        prefs[waitStatusKey]?.let { raw -> runCatching { json.decodeFromString<WaitStatus>(raw) }.getOrNull() }
    }

    suspend fun setWaitStatus(status: WaitStatus) {
        context.settingsDataStore.edit { prefs -> prefs[waitStatusKey] = json.encodeToString(status) }
    }

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
        context.settingsDataStore.edit { prefs ->
            prefs.remove(pendingWaitKey)
            prefs.remove(waitStatusKey)
        }
    }

    companion object {
        /** design-spec 3.6: за замовчуванням 10 хв. */
        const val DEFAULT_SNOOZE_MINUTES = 10
    }
}
