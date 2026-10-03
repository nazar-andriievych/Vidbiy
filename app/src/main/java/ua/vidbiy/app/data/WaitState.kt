package ua.vidbiy.app.data

import kotlinx.serialization.json.Json

/**
 * Чиста частина зберігання очікувань: розбір і зміна списків без жодного Android.
 * Окремо від [SettingsRepository], щоб її можна було перевірити звичайним unit-тестом.
 */
internal object WaitState {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Список очікувань із сховища. [raw] — новий формат (масив); [legacyRaw] — версії, де очікування
     * було одне (об'єкт). Зіпсований запис — порожній список: краще втратити очікування, ніж упасти.
     */
    fun decodeWaits(raw: String?, legacyRaw: String?): List<PendingWait> {
        if (raw != null) return runCatching { json.decodeFromString<List<PendingWait>>(raw) }.getOrDefault(emptyList())
        return legacyRaw
            ?.let { runCatching { json.decodeFromString<PendingWait>(it) }.getOrNull() }
            ?.let(::listOf)
            .orEmpty()
    }

    fun decodeStatuses(raw: String?, legacyRaw: String?): List<WaitStatus> {
        if (raw != null) return runCatching { json.decodeFromString<List<WaitStatus>>(raw) }.getOrDefault(emptyList())
        return legacyRaw
            ?.let { runCatching { json.decodeFromString<WaitStatus>(it) }.getOrNull() }
            ?.let(::listOf)
            .orEmpty()
    }

    fun encodeWaits(waits: List<PendingWait>): String = json.encodeToString(waits)

    fun encodeStatuses(statuses: List<WaitStatus>): String = json.encodeToString(statuses)

    /** Додає очікування або замінює те, що вже є в цього будильника. */
    fun withWait(waits: List<PendingWait>, wait: PendingWait): List<PendingWait> =
        waits.filter { it.alarmId != wait.alarmId } + wait

    /**
     * Оновлює стан очікування. Для очікування, якого вже немає, нічого не міняє: запізнілий запис
     * служби, що саме зупинялася, лишив би по собі сміття.
     */
    fun withStatus(waits: List<PendingWait>, statuses: List<WaitStatus>, status: WaitStatus): List<WaitStatus> {
        if (waits.none { it.alarmId == status.alarmId }) return statuses
        return statuses.filter { it.alarmId != status.alarmId } + status
    }

    fun withoutWait(waits: List<PendingWait>, alarmId: Long): List<PendingWait> = waits.filter { it.alarmId != alarmId }

    fun withoutStatus(statuses: List<WaitStatus>, alarmId: Long): List<WaitStatus> = statuses.filter { it.alarmId != alarmId }

    /** Зіпсований запис — порожній список, як і з очікуваннями. */
    fun decodeSnoozes(raw: String?): List<PendingSnooze> =
        raw?.let { runCatching { json.decodeFromString<List<PendingSnooze>>(it) }.getOrNull() }.orEmpty()

    fun encodeSnoozes(snoozes: List<PendingSnooze>): String = json.encodeToString(snoozes)

    /** У будильника одне відкладення: нове (відклали ще раз) замінює попереднє. */
    fun withSnooze(snoozes: List<PendingSnooze>, snooze: PendingSnooze): List<PendingSnooze> =
        snoozes.filter { it.alarmId != snooze.alarmId } + snooze

    fun withoutSnooze(snoozes: List<PendingSnooze>, alarmId: Long): List<PendingSnooze> = snoozes.filter { it.alarmId != alarmId }
}
