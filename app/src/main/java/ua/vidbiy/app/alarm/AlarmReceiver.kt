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
import ua.vidbiy.app.data.Alarm

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
        if (intent.action != ACTION_FIRE && intent.action != ACTION_FIRE_SNOOZE) return

        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID)
        val isSnooze = intent.action == ACTION_FIRE_SNOOZE
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

                // Відкладений дзвінок нічого не переплановує: свій наступний раз
                // будильник уже отримав, коли задзвонив уперше.
                if (!isSnooze) {
                    if (alarm.days.isEmpty()) {
                        app.alarmsRepository.setEnabled(alarm.id, false)
                    } else {
                        AlarmScheduler(context).schedule(alarm)
                    }
                }

                // Перемикач «враховувати тривоги» тут поки не діє: будильник дзвонить одразу.
                // Так само він поводитиметься, коли даних про тривогу немає (NFR-1),
                // тож це безпечна проміжна поведінка. Очікування відбою — наступний крок.
                AlarmRingService.startRinging(context, alarm)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "ua.vidbiy.app.action.FIRE_ALARM"
        const val ACTION_FIRE_SNOOZE = "ua.vidbiy.app.action.FIRE_SNOOZE"
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}
