package ua.vidbiy.app.alarm

import android.app.Activity
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
import ua.vidbiy.app.data.WaitFor
import java.time.LocalDateTime

/**
 * Лише debug-збірка: ставить і запускає будильники з adb, щоб сценарії docs/testing.md
 * не чекали на прокручування коліс часу. Є лише в src/debug (класу немає в release-APK)
 * і приймає broadcast тільки від відправника з дозволом DUMP — його має adb shell, а не застосунки.
 *
 *   R=ua.vidbiy.app.debug/ua.vidbiy.app.alarm.DebugAlarmReceiver
 *   adb shell am broadcast -n $R --es cmd add [--ei in 1] [--ez alerts true] [--es wait RED_ONLY]
 *       [--ei pause 0] [--ei deadline 5] [--es days 1234567]
 *       — будильник на початок хвилини через `in` хв (основне місце; deadline — хв після будильника)
 *   adb shell am broadcast -n $R --es cmd fire --el id 3 [--ei sec 2]  — спрацювати через sec с
 *   adb shell am broadcast -n $R --es cmd delete --el id 3
 *   adb shell am broadcast -n $R --es cmd list
 *
 * Відповідь — у полі `data=` виводу `am broadcast`.
 */
class DebugAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VidbiyApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val reply = try {
                app.dataReady.await()
                handle(app, intent)
            } catch (e: Exception) {
                "error: ${e.message}"
            }
            Log.i(TAG, reply)
            pending.setResult(Activity.RESULT_OK, reply, null)
            pending.finish()
        }
    }

    private suspend fun handle(app: VidbiyApplication, intent: Intent): String {
        val repo = app.alarmsRepository
        return when (intent.getStringExtra("cmd")) {
            "add" -> {
                val at = LocalDateTime.now().plusMinutes(intent.getIntExtra("in", 1).toLong())
                val primary = app.placesRepository.current().primary
                val alarmMinute = at.hour * 60 + at.minute
                val days = intent.getStringExtra("days").orEmpty().mapNotNull { it.digitToIntOrNull() }.toSet()
                val alarm = repo.save(
                    Alarm(
                        hour = at.hour,
                        minute = at.minute,
                        days = days,
                        respectAlerts = intent.getBooleanExtra("alerts", true),
                        region = primary?.region,
                        placeId = primary?.id,
                        waitFor = WaitFor.valueOf(intent.getStringExtra("wait") ?: WaitFor.RED_AND_YELLOW.name),
                        pauseMinutes = intent.getIntExtra("pause", 0),
                        deadlineMinute = intent.getIntExtra("deadline", -1).takeIf { it >= 0 }
                            ?.let { (alarmMinute + it) % (24 * 60) },
                    ),
                )
                app.alarmScheduler.applyEdit(alarm)
                "added id=${alarm.id} at=%02d:%02d region=${alarm.region?.uid}".format(alarm.hour, alarm.minute)
            }
            "fire" -> {
                val id = intent.getLongExtra("id", Alarm.NEW_ID)
                val sec = intent.getIntExtra("sec", 2)
                app.findAlarm(id) ?: return "no alarm id=$id"
                app.alarmScheduler.fireAt(id, System.currentTimeMillis() + sec * 1_000L)
                "fire id=$id in ${sec}s"
            }
            "delete" -> {
                val id = intent.getLongExtra("id", Alarm.NEW_ID)
                app.alarmScheduler.cancel(id)
                Snoozes.cancel(app, id).join()
                repo.delete(id)
                "deleted id=$id"
            }
            "list" -> repo.alarms.first().let { alarms ->
                alarms.joinToString("; ") {
                    "id=${it.id} %02d:%02d on=${it.enabled} alerts=${it.respectAlerts} days=${it.days} region=${it.region?.uid}"
                        .format(it.hour, it.minute)
                }.ifEmpty { "no alarms" }
            }
            else -> "unknown cmd; use add|fire|delete|list"
        }
    }

    private companion object {
        const val TAG = "VidbiyDebug"
    }
}
