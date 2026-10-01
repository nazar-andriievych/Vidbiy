package ua.vidbiy.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.vidbiy.app.VidbiyApplication
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.DecisionEntry
import ua.vidbiy.app.data.DecisionLog
import ua.vidbiy.app.data.PendingWait
import ua.vidbiy.app.data.shortTitle
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
        // Разовий режим не має власного розкладу: сюди він потрапляє лише з відкладення
        // або страховки очікування.

        val app = context.applicationContext as VidbiyApplication
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.dataReady.await()
                val live = app.findAlarm(alarmId)
                // Прочитати до дзвінка: дзвінок зупиняє службу очікування, і вона стирає свій стан.
                val wait = app.settingsRepository.currentPendingWait(alarmId)
                val lastLevel = app.settingsRepository.currentWaitStatus(alarmId)?.level
                if (live == null) {
                    // Будильник видалили, а спрацювання лишилося — просто мовчимо.
                    return@launch
                }
                // Страховка очікування дзвонить тим будильником, що чекав; нове спрацювання — поточним.
                val alarm = wait?.alarm?.takeIf { !isRegularFire } ?: live

                // Відкладення й крайній час нічого не переплановують: свій наступний раз
                // будильник уже отримав, коли задзвонив уперше.
                if (isRegularFire) {
                    if (alarm.days.isEmpty()) {
                        // Дату теж знімаємо: інакше вимкнений будильник пам'ятав би минулу дату,
                        // а відкритий для правки (навіть під час очікування) не давав би зберегти.
                        app.alarmsRepository.updateWhere({ it.id == alarm.id }) { it.copy(enabled = false, date = null) }
                    } else {
                        AlarmScheduler(context).schedule(alarm)
                    }
                }

                val region = alarm.region
                val waitForAllClear = isRegularFire && alarm.respectAlerts && region != null

                Log.i(
                    TAG,
                    "Спрацювання ${intent.action}: будильник=$alarmId, " +
                        "враховувати тривоги=${alarm.respectAlerts}, регіон=${region?.uid}",
                )
                app.decisionLog.log(
                    DecisionEntry(
                        at = DecisionLog.now(),
                        event = "fire",
                        alarmId = alarm.id,
                        region = region?.uid,
                        covering = region?.coveringUids?.sorted().orEmpty(),
                        waitFor = alarm.waitFor.name,
                        pauseMinutes = alarm.pauseMinutes,
                        note = "${intent.action?.substringAfterLast('.')}, враховувати тривоги=${alarm.respectAlerts}",
                    ),
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
                        alarm = alarm,
                    )
                    AlarmScheduler(context).scheduleDeadline(alarm.id, wait.giveUpAtMillis())
                    AlarmWaitService.startWaiting(context, wait)
                } else {
                    // Регіон не обрано, тривоги не враховуються, відкладений дзвінок
                    // або страховка очікування (крайній час / доба).
                    val reason = if (intent.action == ACTION_FIRE_DEADLINE && wait != null) {
                        val placeName = app.placesRepository.current().byId(alarm.placeId)?.name
                            ?: region?.shortTitle
                        val deadline = wait.deadlineMillis
                        // Крайній час — якщо він настав; інакше спрацювала доба очікування (FR-17).
                        if (deadline != null && System.currentTimeMillis() >= deadline - 60_000L) {
                            RingReason(RingReason.Kind.DEADLINE, placeName, lastLevel, deadlineMillis = deadline)
                        } else {
                            RingReason(RingReason.Kind.TOO_LONG, placeName, lastLevel, oneShot = alarm.id == OneShot.ONE_SHOT_ID)
                        }
                    } else {
                        RingReason.Plain
                    }
                    AlarmRingService.startRinging(context, alarm, reason.copy(oneShot = alarm.id == OneShot.ONE_SHOT_ID))
                    app.decisionLog.log(
                        DecisionEntry(
                            at = DecisionLog.now(),
                            event = "ring",
                            alarmId = alarm.id,
                            region = region?.uid,
                            note = "${intent.action?.substringAfterLast('.')}: ${reason.kind}",
                        ),
                    )
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
