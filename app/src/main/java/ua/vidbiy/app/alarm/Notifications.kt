package ua.vidbiy.app.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Канали нотифікацій. Канал — це «налаштування гучності й важливості», яким керує
 * користувач; створюється один раз і після цього його параметри вже не змінити з коду.
 */
object Notifications {

    /** Дзвінок будильника: максимальна важливість, бо тягне за собою повноекранний інтент. */
    const val CHANNEL_ALARM = "alarm"

    /** Очікування відбою: тиха нотифікація, яка просто висить зі станом (FR-8). */
    const val CHANNEL_WAITING = "waiting"

    /** Відкладений дзвінок: тихе сповіщення «Відкладено до 15:46». */
    const val CHANNEL_SNOOZE = "snooze"

    /** Дзвінок, який ніхто не вимкнув навіть після автовідкладень (FR-21a). */
    const val CHANNEL_MISSED = "missed"

    const val ALARM_NOTIFICATION_ID = 1
    /** Сповіщення очікування разового режиму; очікування будильників — див. [waitingNotificationId]. */
    const val WAITING_NOTIFICATION_ID = 2

    /** Кожне очікування має своє сповіщення зі своїми кнопками. Id будильників — додатні. */
    fun waitingNotificationId(alarmId: Long): Int =
        if (alarmId == OneShot.ONE_SHOT_ID) WAITING_NOTIFICATION_ID else WAITING_NOTIFICATION_BASE_ID + alarmId.toInt()

    private const val WAITING_NOTIFICATION_BASE_ID = 1000

    /** Сповіщення відкладення — своє в кожного будильника, окремо від очікування. */
    fun snoozeNotificationId(alarmId: Long): Int =
        if (alarmId == OneShot.ONE_SHOT_ID) SNOOZE_ONE_SHOT_NOTIFICATION_ID else SNOOZE_NOTIFICATION_BASE_ID + alarmId.toInt()

    private const val SNOOZE_ONE_SHOT_NOTIFICATION_ID = 3
    private const val SNOOZE_NOTIFICATION_BASE_ID = 1_000_000

    /** «Пропущений будильник» — один на будильник: новий пропуск замінює старий. */
    fun missedNotificationId(alarmId: Long): Int =
        if (alarmId == OneShot.ONE_SHOT_ID) MISSED_ONE_SHOT_NOTIFICATION_ID else MISSED_NOTIFICATION_BASE_ID + alarmId.toInt()

    private const val MISSED_ONE_SHOT_NOTIFICATION_ID = 4
    private const val MISSED_NOTIFICATION_BASE_ID = 2_000_000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            context.getString(ua.vidbiy.app.R.string.channel_alarm),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(ua.vidbiy.app.R.string.channel_alarm_description)
            // Звук і вібрацію веде служба дзвінка: вона вміє програвати обрану мелодію
            // потоком будильника, який чути навіть у режимі «Не турбувати» (NFR-2).
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(alarm)

        val waiting = NotificationChannel(
            CHANNEL_WAITING,
            context.getString(ua.vidbiy.app.R.string.channel_waiting),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(ua.vidbiy.app.R.string.channel_waiting_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(waiting)

        val snooze = NotificationChannel(
            CHANNEL_SNOOZE,
            context.getString(ua.vidbiy.app.R.string.channel_snooze),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(ua.vidbiy.app.R.string.channel_snooze_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(snooze)

        // Звичайна важливість: видно в рядку стану й у шторці, але без звуку —
        // будильник і так дзвонив пів години.
        val missed = NotificationChannel(
            CHANNEL_MISSED,
            context.getString(ua.vidbiy.app.R.string.channel_missed),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(ua.vidbiy.app.R.string.channel_missed_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(missed)
    }
}
