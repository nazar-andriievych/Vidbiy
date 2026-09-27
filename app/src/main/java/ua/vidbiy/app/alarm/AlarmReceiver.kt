package ua.vidbiy.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.vidbiy.app.VidbiyApplication
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.PendingWait
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Сюди система стукає в момент спрацювання будильника.
 *
 * BroadcastReceiver живе лічені секунди й має віддати керування якнайшвидше, тож уся
 * робота — прочитати будильник і запустити службу дзвінка. `goAsync()` просить систему
 * потримати процес живим, поки не завершиться корутина: без нього застосунок можуть
 * приспати просто посеред читання сховища.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID)
        val isRegularFire = intent.action == ACTION_FIRE
        if (alarmId == Alarm.NEW_ID) return

        val app = context.applicationContext as VidbiyApplication
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val alarm = app.alarmsRepository.alarms.first().firstOrNull { it.id == alarmId }
                if (alarm == null) {
                    // Будильник видалили, а спрацювання лишилося — просто мовчимо.
                    return@launch
                }

                // Відкладення й крайній час нічого не переплановують: свій наступний раз
                // будильник уже отримав, коли задзвонив уперше.
                if (isRegularFire) {
                    if (alarm.days.isEmpty()) {
                        app.alarmsRepository.setEnabled(alarm.id, false)
                    } else {
                        AlarmScheduler(context).schedule(alarm)
                    }
                }

                val region = app.settingsRepository.selectedRegion.first()
                val waitForAllClear = isRegularFire && alarm.respectAlerts && region != null

                Log.i(
                    TAG,
                    "Спрацювання ${intent.action}: будильник=$alarmId, " +
                        "враховувати тривоги=${alarm.respectAlerts}, регіон=${region?.uid}",
                )

                if (waitForAllClear) {
                    // Перша перевірка тривоги — вже всередині служби: якщо тривоги немає,
                    // вона задзвонить одразу, а якщо є — чекатиме відбою.
                    val now = LocalDateTime.now()
                    val wait = PendingWait(
                        alarmId = alarm.id,
                        deadlineMillis = alarm.deadlineAfter(now)
                            ?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
                        startedAtMillis = System.currentTimeMillis(),
                    )
                    AlarmScheduler(context).scheduleDeadline(alarm.id, wait.giveUpAtMillis())
                    AlarmWaitService.startWaiting(context, wait)
                } else {
                    // Регіон не обрано або тривоги не враховуються — звичайний будильник.
                    AlarmRingService.startRinging(context, alarm)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "VidbiyAlarm"

        const val ACTION_FIRE = "ua.vidbiy.app.action.FIRE_ALARM"
        const val ACTION_FIRE_SNOOZE = "ua.vidbiy.app.action.FIRE_SNOOZE"
        const val ACTION_FIRE_DEADLINE = "ua.vidbiy.app.action.FIRE_DEADLINE"
        const val EXTRA_ALARM_ID = "alarm_id"

        private val HANDLED_ACTIONS = setOf(ACTION_FIRE, ACTION_FIRE_SNOOZE, ACTION_FIRE_DEADLINE)
    }
}
