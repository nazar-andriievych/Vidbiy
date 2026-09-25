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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

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
                AlarmScheduler(context).scheduleAll(app.alarmsRepository.alarms.first())
                restoreWaiting(context, app)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun restoreWaiting(context: Context, app: VidbiyApplication) {
        val wait = app.settingsRepository.currentPendingWait() ?: return
        val alarm = app.alarmsRepository.alarms.first().firstOrNull { it.id == wait.alarmId }
        if (alarm == null) {
            app.settingsRepository.clearPendingWait()
            return
        }

        val deadline = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(wait.deadlineMillis),
            ZoneId.systemDefault(),
        )
        if (System.currentTimeMillis() >= wait.deadlineMillis) {
            // Крайній час настав, поки телефон завантажувався — дзвонимо одразу (FR-7).
            app.settingsRepository.clearPendingWait()
            AlarmRingService.startRinging(context, alarm)
            return
        }

        AlarmScheduler(context).scheduleDeadline(alarm.id, deadline)
        AlarmWaitService.startWaiting(context, alarm, deadline)
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
