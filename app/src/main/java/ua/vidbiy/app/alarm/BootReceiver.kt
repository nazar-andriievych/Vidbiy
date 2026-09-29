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

/**
 * Перезавантаження стирає всі зареєстровані спрацювання — система не зберігає їх між
 * запусками. Тому після старту (а також після оновлення застосунку й переведення
 * годинника) ставимо всі ввімкнені будильники наново.
 *
 * Окремо відновлюємо очікування відбою: якщо телефон перезавантажився під час тривоги,
 * будильник, що чекав, інакше зник би разом зі службою й крайнім часом.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        val app = context.applicationContext as VidbiyApplication
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.dataReady.await()
                AlarmScheduler(context).scheduleAll(app.alarmsRepository.alarms.first())
                restoreWaiting(context, app, intent.action)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun restoreWaiting(context: Context, app: VidbiyApplication, action: String?) {
        val wait = app.settingsRepository.currentPendingWait() ?: return
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
            app.settingsRepository.clearPendingWait()
            return
        }

        // Запис зі старої версії не знав, коли почалося очікування: рахуємо від зараз.
        val restored = wait.copy(startedAtMillis = wait.startedAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis())
        if (System.currentTimeMillis() >= restored.giveUpAtMillis()) {
            // Крайній час настав, поки телефон завантажувався — дзвонимо одразу (FR-16).
            app.settingsRepository.clearPendingWait()
            val deadline = restored.deadlineMillis
            val reason = if (deadline != null && System.currentTimeMillis() >= deadline) {
                RingReason(RingReason.Kind.DEADLINE, deadlineMillis = deadline)
            } else {
                RingReason(RingReason.Kind.TOO_LONG)
            }
            AlarmRingService.startRinging(context, alarm, reason)
            return
        }

        AlarmScheduler(context).scheduleDeadline(alarm.id, restored.giveUpAtMillis())
        AlarmWaitService.startWaiting(context, restored)
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            // ACTION_LOCKED_BOOT_COMPLETED тут не підходить: сховище будильників
            // зашифроване ключем користувача й до першого розблокування недоступне.
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
