package ua.vidbiy.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
    /**
     * Будильник таким, яким він був на початку очікування: регіон, рівень, пауза, час.
     * Очікування доживає з ними, хоч би що змінилося потім (регіон місця, основне місце,
     * налаштування разового режиму) — інакше воно тихо перемкнулося б на інші дані.
     * Редагування самого будильника очікування припиняє (FR-7b). null — запис зі старої версії.
     */
    val alarm: Alarm? = null,
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

    fun toJson(): String = json.encodeToString(this)

    companion object {
        const val MAX_WAIT_MILLIS = 24 * 60 * 60 * 1000L

        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(raw: String): PendingWait? = runCatching { json.decodeFromString<PendingWait>(raw) }.getOrNull()
    }
}

/**
 * Відкладений дзвінок (FR-20). Сам дзвінок живе в AlarmManager, але система стирає його
 * під час перезавантаження, оновлення чи примусової зупинки застосунку — а з ним зникло б
 * і відкладення. Запис на диску дає змогу поставити його знову й показати в інтерфейсі.
 */
@Serializable
data class PendingSnooze(
    val alarmId: Long,
    /** Коли задзвонить. */
    val ringAtMillis: Long,
)

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

/**
 * Стан очікування — окремим файлом, бо він не йде в резервну копію Android
 * (res/xml/data_extraction_rules.xml): вчорашнє «зараз чекаю відбою» на новому
 * телефоні лише заплутало б. Налаштування й будильники в копію йдуть.
 */
private val Context.waitDataStore: DataStore<Preferences> by preferencesDataStore(name = WAIT_STORE_NAME)

/** Ім'я файлу стану очікування; те саме ім'я стоїть у правилах резервної копії. */
const val WAIT_STORE_NAME = "wait"

/** Налаштування застосунку: тема, відкладення, разовий режим, стан очікування. */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val regionKey = stringPreferencesKey("selected_region")
    private val proxyUrlKey = stringPreferencesKey("debug_proxy_url")
    // Очікувань може бути кілька одразу (будильники в різних регіонах і разовий режим), тож
    // списки. Ключі «pending_wait» / «wait_status» — версії, де очікування було лише одне:
    // читаємо їх як список з одного запису, а першою ж зміною переписуємо в новий формат.
    private val pendingWaitsKey = stringPreferencesKey("pending_waits")
    private val waitStatusesKey = stringPreferencesKey("wait_statuses")
    private val pendingWaitKey = stringPreferencesKey("pending_wait")
    private val waitStatusKey = stringPreferencesKey("wait_status")
    private val pendingSnoozesKey = stringPreferencesKey("pending_snoozes")
    private val themeModeKey = stringPreferencesKey("theme_mode")
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

    /** Адреса проксі — задається під час збірки (див. app/build.gradle.kts). */
    fun proxyBaseUrl(): String = ProxyConfig.BASE_URL

    /**
     * Старі debug-збірки дозволяли вписати адресу локального воркера прямо в застосунку.
     * Забута адреса змусила б будильник дзвонити з «Немає зв'язку», тож стираємо її.
     */
    suspend fun clearLegacyProxyUrl() {
        context.settingsDataStore.edit { prefs -> prefs.remove(proxyUrlKey) }
    }

    /**
     * Очікування, які тривають просто зараз. Потоком — щоб список будильників показував
     * «чекає відбою» рівно доти, доки служба справді чекає, і сам гасив напис,
     * коли вона зупинилася.
     */
    val pendingWaits: Flow<List<PendingWait>> = context.waitDataStore.data.map { prefs -> readWaits(prefs) }

    /** Що зараз бачить служба про кожне очікування. */
    val waitStatuses: Flow<List<WaitStatus>> = context.waitDataStore.data.map { prefs -> readStatuses(prefs) }

    suspend fun currentPendingWaits(): List<PendingWait> = pendingWaits.first()

    suspend fun currentPendingWait(alarmId: Long): PendingWait? = currentPendingWaits().firstOrNull { it.alarmId == alarmId }

    suspend fun currentWaitStatus(alarmId: Long): WaitStatus? =
        waitStatuses.first().firstOrNull { it.alarmId == alarmId }

    /** Додає очікування або замінює те, що вже є в цього будильника. */
    suspend fun setPendingWait(wait: PendingWait) {
        context.waitDataStore.edit { prefs ->
            writeWaits(prefs, WaitState.withWait(readWaits(prefs), wait))
        }
    }

    /**
     * Оновлює стан очікування. Очікування, якого вже немає, не воскрешає: запізнілий запис
     * служби, що саме зупинялася, лишив би по собі сміття.
     */
    suspend fun setWaitStatus(status: WaitStatus) {
        context.waitDataStore.edit { prefs ->
            writeStatuses(prefs, WaitState.withStatus(readWaits(prefs), readStatuses(prefs), status))
        }
    }

    /** Очікування цього будильника закінчилося (дзвінок, скасування, видалення). Інші не чіпає. */
    suspend fun clearPendingWait(alarmId: Long) {
        context.waitDataStore.edit { prefs ->
            writeWaits(prefs, WaitState.withoutWait(readWaits(prefs), alarmId))
            writeStatuses(prefs, WaitState.withoutStatus(readStatuses(prefs), alarmId))
        }
    }

    /**
     * Відкладені дзвінки. Лежать поруч з очікуваннями й так само не йдуть у резервну копію:
     * «задзвони через 10 хв» на новому телефоні вже нічого не означає.
     */
    val pendingSnoozes: Flow<List<PendingSnooze>> = context.waitDataStore.data.map { prefs ->
        WaitState.decodeSnoozes(prefs[pendingSnoozesKey])
    }

    suspend fun currentPendingSnoozes(): List<PendingSnooze> = pendingSnoozes.first()

    /** Додає відкладення або замінює те, що вже є в цього будильника. */
    suspend fun setPendingSnooze(snooze: PendingSnooze) {
        context.waitDataStore.edit { prefs ->
            writeSnoozes(prefs, WaitState.withSnooze(WaitState.decodeSnoozes(prefs[pendingSnoozesKey]), snooze))
        }
    }

    suspend fun clearPendingSnooze(alarmId: Long) {
        context.waitDataStore.edit { prefs ->
            writeSnoozes(prefs, WaitState.withoutSnooze(WaitState.decodeSnoozes(prefs[pendingSnoozesKey]), alarmId))
        }
    }

    private fun writeSnoozes(prefs: MutablePreferences, snoozes: List<PendingSnooze>) {
        if (snoozes.isEmpty()) prefs.remove(pendingSnoozesKey) else prefs[pendingSnoozesKey] = WaitState.encodeSnoozes(snoozes)
    }

    private fun readWaits(prefs: Preferences): List<PendingWait> =
        WaitState.decodeWaits(prefs[pendingWaitsKey], prefs[pendingWaitKey])

    private fun readStatuses(prefs: Preferences): List<WaitStatus> =
        WaitState.decodeStatuses(prefs[waitStatusesKey], prefs[waitStatusKey])

    private fun writeWaits(prefs: MutablePreferences, waits: List<PendingWait>) {
        prefs.remove(pendingWaitKey)
        if (waits.isEmpty()) prefs.remove(pendingWaitsKey) else prefs[pendingWaitsKey] = WaitState.encodeWaits(waits)
    }

    private fun writeStatuses(prefs: MutablePreferences, statuses: List<WaitStatus>) {
        prefs.remove(waitStatusKey)
        if (statuses.isEmpty()) prefs.remove(waitStatusesKey) else prefs[waitStatusesKey] = WaitState.encodeStatuses(statuses)
    }

    /**
     * Версії до 2026-09-28 тримали стан очікування в загальних налаштуваннях. Переносимо
     * його в окремий файл, щоб оновлення посеред очікування нічого не загубило.
     */
    suspend fun moveLegacyWaitState() {
        var wait: String? = null
        var status: String? = null
        context.settingsDataStore.edit { prefs ->
            wait = prefs[pendingWaitKey]
            status = prefs[waitStatusKey]
            prefs.remove(pendingWaitKey)
            prefs.remove(waitStatusKey)
        }
        if (wait == null && status == null) return
        context.waitDataStore.edit { prefs ->
            wait?.let { prefs[pendingWaitKey] = it }
            status?.let { prefs[waitStatusKey] = it }
        }
    }

    companion object {
        /** design-spec 3.6: за замовчуванням 10 хв. */
        const val DEFAULT_SNOOZE_MINUTES = 10
    }
}
