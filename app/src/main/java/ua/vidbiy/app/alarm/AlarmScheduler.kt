package ua.vidbiy.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ua.vidbiy.app.MainActivity
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.PendingSnooze
import java.time.LocalDateTime

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

    /**
     * Ставить будильник на його найближче спрацювання. Вимкнений — скасовує.
     * Будильник з датою, що минула, теж лише скасовує: вимкнути його в сховищі —
     * справа [AlarmsRepository.disableMissed], планувальник у сховище не пише.
     *
     * Скасовує лише звичайне спрацювання. Разовий будильник вимикається вже в момент дзвінка,
     * а його відкладення, страховка автовідкладення (AlarmRingService), крайній час і «вартовий»
     * очікування мусять пережити звичайне перепланування на старті застосунку. Їх знімають
     * власники: [Snoozes.cancel] — коли людина вимикає, змінює чи видаляє будильник,
     * [cancelDeadline] — коли закінчується очікування, [cancel] — коли будильник видаляють.
     */
    fun schedule(alarm: Alarm, now: LocalDateTime = LocalDateTime.now()) {
        val next = alarm.nextTriggerAt(now)
        if (!alarm.enabled || next == null) {
            alarmManager.cancel(firePendingIntent(alarm.id, Kind.NORMAL))
            return
        }
        setAt(alarm.id, next, Kind.NORMAL)
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

    /**
     * FR-20: відкладений дзвінок на [atMillis]. Момент, що вже минув, система виконує одразу.
     * Записати відкладення на диск і показати його — справа [Snoozes]: планувальник у сховище не пише.
     */
    fun snoozeAt(alarmId: Long, atMillis: Long) {
        // До розблокування після перезавантаження звичайний приймач не запуститься (див. LockedBoot).
        if (LockedBoot.isLocked(context)) {
            LockedBoot.snooze(context, PendingSnooze(alarmId, atMillis))
            return
        }
        setAtMillis(alarmId, atMillis, Kind.SNOOZE)
    }

    /**
     * FR-16: страхувальне спрацювання на момент, коли будильник здається (крайній час або
     * доба очікування). Служба очікування відбою може не дожити до нього (виробник прибив
     * процес, система звільняла пам'ять), а будильник має задзвонити однаково — тож цей
     * момент живе в AlarmManager окремо від неї.
     */
    fun scheduleDeadline(alarmId: Long, atMillis: Long) {
        setAtMillis(alarmId, atMillis, Kind.DEADLINE)
    }

    /**
     * Скасовує страховку очікування: і крайній час, і «вартового». Очікування закінчується
     * (дзвінок, відкладення, скасування) в тих самих місцях для обох, тож вони йдуть разом.
     */
    fun cancelDeadline(alarmId: Long) {
        alarmManager.cancel(firePendingIntent(alarmId, Kind.DEADLINE))
        cancelWatchdog(alarmId)
    }

    /**
     * «Вартовий»: таймер, який служба очікування відсуває вперед при кожному опитуванні.
     * Поки служба жива, він не встигає спрацювати. Якщо її вбила чи приспала система, відсувати
     * нікому, і за [atMillis] спрацює AlarmReceiver: розбудить очікування або задзвонить
     * (див. [watchdogAction]) — замість мовчання до крайнього часу чи доби.
     *
     * `setAlarmClock`, а не `setExactAndAllowWhileIdle`: застосунку в «глибокому сні» Samsung
     * система доставляє лише будильникові таймери (перевірено 2026-10-09), а звичайний вартовий
     * так і лишався в черзі. Ціна — поки будильник чекає відбою, «наступний будильник» у рядку
     * стану показує час вартового (за кілька хвилин).
     *
     * Окремий PendingIntent з тим самим ключем (будильник + тип) — кожне виставлення замінює попереднє.
     */
    fun scheduleWatchdog(alarmId: Long, atMillis: Long) {
        val operation = watchdogPendingIntent(alarmId)
        if (canScheduleExact()) {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showAlarmsPendingIntent()), operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
        }
    }

    fun cancelWatchdog(alarmId: Long) {
        alarmManager.cancel(watchdogPendingIntent(alarmId))
    }

    /**
     * Запустити службу дзвінка [ringIntent] через будильниковий таймер «за секунду»: AlarmManager
     * сам стартує її як службу переднього плану у своєму дозволеному вікні. Запасний шлях, коли
     * Android не дав запустити дзвінок напряму (AlarmRingService.startOrDefer).
     */
    fun ringSoon(alarmId: Long, ringIntent: Intent) {
        val operation = PendingIntent.getForegroundService(
            context,
            RING_SOON_REQUEST_BASE + alarmId.toInt(),
            ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = System.currentTimeMillis() + 1_000L
        if (canScheduleExact()) {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, showAlarmsPendingIntent()), operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    fun cancel(alarmId: Long) {
        Kind.entries.forEach { alarmManager.cancel(firePendingIntent(alarmId, it)) }
        cancelWatchdog(alarmId)
    }

    // requestCode не з формули firePendingIntent: її множник — кількість видів, і зміна його
    // осиротила б уже виставлені будильники після оновлення застосунку.
    private fun watchdogPendingIntent(alarmId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE_WATCHDOG
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_BASE + alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val WATCHDOG_REQUEST_BASE = 1_000_000_000
        const val RING_SOON_REQUEST_BASE = 1_100_000_000
    }

    fun cancelSnooze(alarmId: Long) {
        if (LockedBoot.isLocked(context)) LockedBoot.cancelSnooze(context, alarmId)
        alarmManager.cancel(firePendingIntent(alarmId, Kind.SNOOZE))
    }

    private fun setAt(alarmId: Long, at: LocalDateTime, kind: Kind) {
        setAtMillis(alarmId, at.toEpochMillis(), kind)
    }

    private fun setAtMillis(alarmId: Long, triggerAtMillis: Long, kind: Kind) {
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
