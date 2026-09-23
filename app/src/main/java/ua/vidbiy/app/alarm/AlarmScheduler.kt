package ua.vidbiy.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ua.vidbiy.app.MainActivity
import ua.vidbiy.app.data.Alarm
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Реєструє спрацювання в системному AlarmManager.
 *
 * Використовуємо `setAlarmClock` — це найсильніший режим: система не відкладає такі
 * спрацювання в Doze (режимі глибокого сну) і показує іконку будильника в рядку стану.
 * Інші режими (`set`, `setExact`) система має право зсунути на хвилини або десятки хвилин,
 * а для будильника це провал.
 *
 * PendingIntent — це «дозвіл» системі запустити наш код від нашого імені; приблизно як
 * делегат, який ми віддаємо назовні. AlarmManager розрізняє спрацювання саме за ним,
 * причому додаткові поля (extras) при порівнянні не враховуються — рахуються дія,
 * компонент і requestCode. Тому три види спрацювання (звичайне, відкладення, крайній час)
 * мають і різні дії, і різні requestCode: інакше відкладення на 5 хв затерло б наступний ранок.
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Чи дозволені точні спрацювання. На Android 12 дозвіл можна відкликати в налаштуваннях. */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** Ставить будильник на його найближче спрацювання. Вимкнений — скасовує. */
    fun schedule(alarm: Alarm, now: LocalDateTime = LocalDateTime.now()) {
        if (!alarm.enabled) {
            cancel(alarm.id)
            return
        }
        setAt(alarm.id, alarm.nextTriggerAt(now), Kind.NORMAL)
    }

    fun scheduleAll(alarms: List<Alarm>, now: LocalDateTime = LocalDateTime.now()) {
        alarms.forEach { schedule(it, now) }
    }

    /**
     * Після редагування будильника: відкладення, яке лишилося від попереднього дзвінка,
     * більше не актуальне. Звичайне перепланування (старт застосунку, перезавантаження)
     * відкладення не чіпає — інакше воно зникало б щоразу, коли користувач відкриває застосунок.
     */
    fun applyEdit(alarm: Alarm) {
        cancelSnooze(alarm.id)
        schedule(alarm)
    }

    /** FR-9: відкладення на [minutes] хвилин. */
    fun snooze(alarmId: Long, minutes: Int, now: LocalDateTime = LocalDateTime.now()) {
        setAt(alarmId, now.plusMinutes(minutes.toLong()), Kind.SNOOZE)
    }

    /**
     * FR-7: страхувальне спрацювання на крайній час. Служба очікування відбою може не дожити
     * до нього (виробник прибив процес, система звільняла пам'ять), а будильник має задзвонити
     * однаково — тож крайній час живе в AlarmManager окремо від неї.
     */
    fun scheduleDeadline(alarmId: Long, at: LocalDateTime) {
        setAt(alarmId, at, Kind.DEADLINE)
    }

    fun cancelDeadline(alarmId: Long) {
        alarmManager.cancel(firePendingIntent(alarmId, Kind.DEADLINE))
    }

    fun cancel(alarmId: Long) {
        Kind.entries.forEach { alarmManager.cancel(firePendingIntent(alarmId, it)) }
    }

    private fun cancelSnooze(alarmId: Long) {
        alarmManager.cancel(firePendingIntent(alarmId, Kind.SNOOZE))
    }

    private fun setAt(alarmId: Long, at: LocalDateTime, kind: Kind) {
        val triggerAtMillis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val operation = firePendingIntent(alarmId, kind)

        if (canScheduleExact()) {
            val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showAlarmsPendingIntent())
            alarmManager.setAlarmClock(info, operation)
        } else {
            // Дозвіл на точні спрацювання відкликаний. Дзвонимо хоч так: setAndAllowWhileIdle
            // прокидає застосунок навіть у Doze, але час може зсунутися на кілька хвилин.
            // Інтерфейс про це попереджає окремо.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
    }

    private fun firePendingIntent(alarmId: Long, kind: Kind): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = kind.action
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            context,
            (alarmId * Kind.entries.size + kind.ordinal).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private enum class Kind(val action: String) {
        NORMAL(AlarmReceiver.ACTION_FIRE),
        SNOOZE(AlarmReceiver.ACTION_FIRE_SNOOZE),
        DEADLINE(AlarmReceiver.ACTION_FIRE_DEADLINE),
    }

    /** Куди веде дотик до іконки будильника в системному годиннику. */
    private fun showAlarmsPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
