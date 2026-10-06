package ua.vidbiy.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import ua.vidbiy.app.MainActivity
import ua.vidbiy.app.VidbiyApplication
import ua.vidbiy.app.data.DecisionEntry
import ua.vidbiy.app.data.DecisionLog
import ua.vidbiy.app.data.PendingSnooze
import java.time.LocalDateTime

/**
 * Будильник після перезавантаження, поки телефон ще не розблокували.
 *
 * Android стирає всі спрацювання під час перезавантаження, а звичайний сигнал «телефон
 * увімкнувся» (`BOOT_COMPLETED`) надсилає лише після першого розблокування: до нього
 * сховище будильників зашифроване ключем, що залежить від PIN. Без цього файлу телефон,
 * що перезавантажився вночі (оновлення, автоперезапуск Samsung), до ранку стояв би без
 * будильника.
 *
 * Тому, поки телефон розблокований, ми тримаємо копію розкладу ([LockedBootPlan]) у сховищі,
 * доступному без PIN (device-protected storage). Після перезавантаження `LOCKED_BOOT_COMPLETED`
 * ставить спрацювання з неї, а [LockedAlarmReceiver] дзвонить — без перевірки тривоги
 * й типовим звуком будильника: ні регіону, ні мелодії до розблокування не видно. Це fail-safe (NFR-1, NFR-2a).
 *
 * Після розблокування [handOver] переносить те, що сталося, у звичайне сховище (одноразовий
 * будильник вимкнути, очікування зняти, відкладення перенести) і прибирає ці спрацювання —
 * далі працює звичайний розклад.
 */
object LockedBoot {
    private const val TAG = "VidbiyLockedBoot"
    private const val PREFS = "locked_boot"
    private const val KEY_PLAN = "plan"
    private const val KEY_FIRED = "fired"
    private const val KEY_SNOOZES = "snoozes"

    /** Окремі коди запиту: не перетинаються ні з [AlarmScheduler], ні з його «вартовим». */
    private const val ALARM_REQUEST_BASE = 1_200_000_000
    private const val EXTRA_REQUEST_BASE = 1_400_000_000

    private val json = Json { ignoreUnknownKeys = true }

    /** Чи телефон ще не розблокований після ввімкнення. */
    fun isLocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == false

    /** Оновити копію розкладу. Викликається, поки телефон розблокований, на кожну зміну. */
    fun savePlan(context: Context, plan: LockedBootPlan) {
        val raw = json.encodeToString(LockedBootPlan.serializer(), plan)
        val prefs = prefs(context)
        if (prefs.getString(KEY_PLAN, null) != raw) prefs.edit { putString(KEY_PLAN, raw) }
    }

    /** Поставити спрацювання до розблокування (після `LOCKED_BOOT_COMPLETED` і після кожного дзвінка). */
    fun schedule(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        val fires = lockedFires(
            plan = readPlan(prefs),
            lockedSnoozes = readSnoozes(prefs),
            fired = readFired(prefs),
            now = LocalDateTime.now(),
            nowMillis = nowMillis,
        )
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        for (fire in fires) {
            val operation = firePendingIntent(context, fire.alarmId, fire.slot, fire)
            runCatching { alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(fire.atMillis, showAlarms(context)), operation) }
                .recoverCatching {
                    // Android 12: дозвіл на точні спрацювання відкликаний — хоч так, з можливим зсувом
                    // на кілька хвилин (як і в AlarmScheduler).
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire.atMillis, operation)
                }
                .onFailure { Log.e(TAG, "Не вдалося поставити спрацювання до розблокування: $fire", it) }
        }
        Log.i(TAG, "Спрацювання до розблокування: ${fires.size}")
    }

    /** Відкладення з екрана дзвінка до розблокування (FR-20, FR-21a). */
    fun snooze(context: Context, snooze: PendingSnooze) {
        val prefs = prefs(context)
        val snoozes = readSnoozes(prefs).filterNot { it.alarmId == snooze.alarmId } + snooze
        // commit: приймач живе лічені секунди, запис має лягти до його кінця.
        prefs.edit(commit = true) { putString(KEY_SNOOZES, json.encodeToString(snoozes)) }
        schedule(context)
    }

    /** «Вимкнути» на екрані дзвінка: відкладення, поставлене до розблокування, більше не потрібне. */
    fun cancelSnooze(context: Context, alarmId: Long) {
        val prefs = prefs(context)
        prefs.edit(commit = true) { putString(KEY_SNOOZES, json.encodeToString(readSnoozes(prefs).filterNot { it.alarmId == alarmId })) }
        context.getSystemService(AlarmManager::class.java)
            ?.cancel(firePendingIntent(context, alarmId, LockedFire.Slot.EXTRA, null))
        // На тому ж місці могло чекати інше спрацювання цього будильника — поставити його знову.
        schedule(context)
    }

    /** Будильник задзвонив до розблокування: запам'ятати й поставити наступне спрацювання. */
    internal fun onFired(context: Context, fire: LockedFire) {
        val prefs = prefs(context)
        val fired = readFired(prefs) + LockedFired(fire.alarmId, fire.kind, fire.atMillis)
        val snoozes = readSnoozes(prefs).filterNot { fire.kind == LockedFire.Kind.SNOOZE && it.alarmId == fire.alarmId }
        prefs.edit(commit = true) {
            putString(KEY_FIRED, json.encodeToString(fired))
            putString(KEY_SNOOZES, json.encodeToString(snoozes))
        }
        schedule(context)
    }

    internal fun snoozeMinutes(context: Context): Int = readPlan(prefs(context)).snoozeMinutes

    /**
     * Телефон розблокували: перенести в справжнє сховище все, що сталося до розблокування,
     * і прибрати ці спрацювання — далі будильники ставить звичайний розклад.
     * Викликати до того, як застосунок почне ставити будильники (див. [VidbiyApplication.onUserUnlocked]).
     */
    suspend fun handOver(app: VidbiyApplication) {
        val prefs = prefs(app)
        val plan = readPlan(prefs)
        val fired = readFired(prefs)
        val snoozes = readSnoozes(prefs)

        for (f in fired) {
            app.decisionLog.log(
                DecisionEntry(at = DecisionLog.now(f.atMillis), event = "locked_ring", alarmId = f.alarmId, note = f.kind.name),
            )
            when (f.kind) {
                // Задзвонив одноразовий — вимикаємо, як це зробив би AlarmReceiver (FR-1a).
                LockedFire.Kind.ALARM -> app.alarmsRepository.updateWhere({ it.id == f.alarmId && it.days.isEmpty() }) {
                    it.copy(enabled = false, date = null)
                }
                // Очікування вже закінчилось дзвінком — не відновлювати його.
                LockedFire.Kind.WAIT -> app.settingsRepository.clearPendingWait(f.alarmId)
                LockedFire.Kind.SNOOZE ->
                    if (app.settingsRepository.currentPendingSnoozes().any { it.alarmId == f.alarmId && it.ringAtMillis == f.atMillis }) {
                        app.settingsRepository.clearPendingSnooze(f.alarmId)
                    }
            }
        }
        // Відкладення, зроблені до розблокування, — у звичайний список: їх видно й поставить Snoozes.restore.
        for (snooze in snoozes) app.settingsRepository.setPendingSnooze(snooze)

        val alarmManager = app.getSystemService(AlarmManager::class.java)
        val ids = (plan.alarms + plan.waiting).map { it.id } + plan.snoozes.map { it.alarmId } + snoozes.map { it.alarmId }
        for (id in ids.distinct()) {
            LockedFire.Slot.entries.forEach { slot -> alarmManager?.cancel(firePendingIntent(app, id, slot, null)) }
        }
        prefs.edit(commit = true) {
            remove(KEY_FIRED)
            remove(KEY_SNOOZES)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readPlan(prefs: SharedPreferences): LockedBootPlan =
        prefs.getString(KEY_PLAN, null)
            ?.let { runCatching { json.decodeFromString(LockedBootPlan.serializer(), it) }.getOrNull() }
            ?: LockedBootPlan()

    private fun readFired(prefs: SharedPreferences): List<LockedFired> =
        prefs.getString(KEY_FIRED, null)?.let { runCatching { json.decodeFromString<List<LockedFired>>(it) }.getOrNull() }.orEmpty()

    private fun readSnoozes(prefs: SharedPreferences): List<PendingSnooze> =
        prefs.getString(KEY_SNOOZES, null)?.let { runCatching { json.decodeFromString<List<PendingSnooze>>(it) }.getOrNull() }.orEmpty()

    /** [fire] — що саме дзвонить; для скасування не потрібне: AlarmManager порівнює без extras. */
    private fun firePendingIntent(context: Context, alarmId: Long, slot: LockedFire.Slot, fire: LockedFire?): PendingIntent {
        val intent = Intent(context, LockedAlarmReceiver::class.java).apply {
            action = LockedAlarmReceiver.ACTION_FIRE_LOCKED
            fire?.let { putExtra(LockedAlarmReceiver.EXTRA_FIRE, json.encodeToString(LockedFire.serializer(), it)) }
        }
        val base = if (slot == LockedFire.Slot.ALARM) ALARM_REQUEST_BASE else EXTRA_REQUEST_BASE
        return PendingIntent.getBroadcast(
            context,
            base + alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showAlarms(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun decodeFire(raw: String?): LockedFire? =
        raw?.let { runCatching { json.decodeFromString(LockedFire.serializer(), it) }.getOrNull() }
}

/**
 * Спрацювання, поставлене до розблокування. Працює й на заблокованому після перезавантаження
 * телефоні (directBootAware у маніфесті), тож не чіпає звичайного сховища.
 */
class LockedAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE_LOCKED) return
        val fire = LockedBoot.decodeFire(intent.getStringExtra(EXTRA_FIRE)) ?: return
        Log.i("VidbiyLockedBoot", "Дзвінок до розблокування: $fire")
        LockedBoot.onFired(context, fire)
        AlarmRingService.startRingingLocked(context, fire, LockedBoot.snoozeMinutes(context))
    }

    companion object {
        const val ACTION_FIRE_LOCKED = "ua.vidbiy.app.action.FIRE_LOCKED"
        const val EXTRA_FIRE = "fire"
    }
}
