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

    const val ALARM_NOTIFICATION_ID = 1

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
    }
}
