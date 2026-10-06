package ua.vidbiy.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.vidbiy.app.VidbiyApplication
import ua.vidbiy.app.data.DecisionEntry
import ua.vidbiy.app.data.DecisionLog
import ua.vidbiy.app.data.PendingWait

/**
 * Перезавантаження стирає всі зареєстровані спрацювання — система не зберігає їх між
 * запусками. Тому після старту (а також після оновлення застосунку й переведення
 * годинника) ставимо всі ввімкнені будильники наново.
 *
 * Відкладені дзвінки AlarmManager теж забуває — ставимо їх знову з диска ([Snoozes]).
 *
 * До першого розблокування сховище недоступне — тоді будильники ставить [LockedBoot].
 *
 * Окремо відновлюємо очікування відбою: якщо телефон перезавантажився під час тривоги,
 * будильник, що чекав, інакше зник би разом зі службою й крайнім часом.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED && intent.action !in HANDLED_ACTIONS) return
        if (LockedBoot.isLocked(context)) {
            // Телефон увімкнувся (чи переведено годинник), але ще не розблокований: будильники —
            // з копії розкладу (LockedBoot). Звичайний BOOT_COMPLETED прийде після розблокування.
            LockedBoot.schedule(context)
            return
        }
        // Без PIN телефон розблокований одразу, і слідом прийде звичайний BOOT_COMPLETED.
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val app = context.applicationContext as VidbiyApplication
        // Процес міг стартувати ще до розблокування — тоді звичайна робота починається тут.
        app.onUserUnlocked()
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.dataReady.await()
                AlarmScheduler(context).scheduleAll(app.alarmsRepository.disableMissed())
                Snoozes.restore(context, afterReset = true)
                restoreWaiting(context, app, intent.action)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun restoreWaiting(context: Context, app: VidbiyApplication, action: String?) {
        // Очікувань могло бути кілька (різні регіони, разовий режим): відновлюємо кожне.
        for (wait in app.settingsRepository.currentPendingWaits()) restoreWait(context, app, action, wait)
    }

    private suspend fun restoreWait(context: Context, app: VidbiyApplication, action: String?, wait: PendingWait) {
        // Видалений будильник не відновлюємо; живий — з налаштуваннями, з якими він чекав.
        val alarm = app.findAlarm(wait.alarmId)?.let { wait.alarm ?: it }
        app.decisionLog.log(
            DecisionEntry(
                at = DecisionLog.now(),
                event = "restore_wait",
                alarmId = wait.alarmId,
                note = "${action?.substringAfterLast('.')}${if (alarm == null) ", будильник видалено" else ""}",
            ),
        )
        if (alarm == null) {
            app.settingsRepository.clearPendingWait(wait.alarmId)
            return
        }

        // Запис зі старої версії не знав, коли почалося очікування: рахуємо від зараз.
        val restored = wait.withKnownStart(System.currentTimeMillis())
        if (System.currentTimeMillis() >= restored.giveUpAtMillis()) {
            // Крайній час настав, поки телефон завантажувався — дзвонимо одразу (FR-16).
            app.settingsRepository.clearPendingWait(wait.alarmId)
            val kind = backstopReason(restored.deadlineMillis, System.currentTimeMillis())
            val reason = RingReason(kind, deadlineMillis = restored.deadlineMillis.takeIf { kind == RingReason.Kind.DEADLINE })
            AlarmRingService.startRinging(context, alarm, reason)
            return
        }

        AlarmScheduler(context).scheduleDeadline(alarm.id, restored.giveUpAtMillis())
        AlarmWaitService.startWaiting(context, restored)
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            // ACTION_LOCKED_BOOT_COMPLETED обробляється окремо: до першого розблокування
            // сховище будильників недоступне, і дзвонить копія розкладу (LockedBoot).
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
