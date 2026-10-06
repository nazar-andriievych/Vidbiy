package ua.vidbiy.app.alarm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ua.vidbiy.app.MainActivity
import ua.vidbiy.app.R
import ua.vidbiy.app.VidbiyApplication
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.DecisionEntry
import ua.vidbiy.app.data.DecisionLog
import ua.vidbiy.app.data.PendingSnooze
import ua.vidbiy.app.ui.formatClock
import ua.vidbiy.app.ui.formatTime

/**
 * Відкладені дзвінки (FR-20): «задзвони через X хв» з екрана дзвінка чи очікування.
 *
 * Сам дзвінок ставить [AlarmScheduler], але AlarmManager забуває все після перезавантаження,
 * оновлення застосунку чи примусової зупинки. Тому відкладення ще й записане на диск
 * ([PendingSnooze]): звідти його ставлять знову ([restore]) і показують у списку та в сповіщенні.
 * Без цього відкладений будильник зникав безслідно — і в інтерфейсі, і насправді.
 */
object Snoozes {
    private const val TAG = "VidbiyAlarm"

    /** Відкладення, проґавлене більш ніж на годину (телефон був вимкнений), уже не дзвонить. */
    private const val STALE_AFTER_MILLIS = 60 * 60_000L

    /**
     * Скільки після моменту дзвінка відкладення вважається «саме зараз дзвонить». Процес, який
     * запустило спрацювання відкладення, теж проходить [restore] на старті — і не має поставити
     * його вдруге, поки [AlarmReceiver] ще не встиг стерти запис.
     */
    private const val FIRING_WINDOW_MILLIS = 60_000L

    /** [autoRepeats] — номер автовідкладення (FR-21a); людина, що відклала сама, починає ланцюжок з 0. */
    fun snooze(
        context: Context,
        alarmId: Long,
        minutes: Int,
        autoRepeats: Int = 0,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val snooze = PendingSnooze(alarmId, nowMillis + minutes * 60_000L, autoRepeats)
        if (LockedBoot.isLocked(context)) {
            // До розблокування звичайного сховища немає — відкладення живе поруч із розкладом.
            LockedBoot.snooze(context, snooze)
            return
        }
        // AlarmManager — одразу й синхронно: служба, яка нас викликала, зараз зупиниться.
        AlarmScheduler(context).snoozeAt(alarmId, snooze.ringAtMillis)
        val app = context.app()
        app.applicationScope.launch {
            app.settingsRepository.setPendingSnooze(snooze)
            show(context, snooze, app.findAlarm(alarmId))
        }
    }

    /** Користувач скасував відкладення, змінив або видалив будильник. */
    fun cancel(context: Context, alarmId: Long): Job {
        AlarmScheduler(context).cancelSnooze(alarmId)
        hide(context, alarmId)
        val app = context.app()
        return app.applicationScope.launch {
            if (app.settingsRepository.currentPendingSnoozes().none { it.alarmId == alarmId }) return@launch
            app.settingsRepository.clearPendingSnooze(alarmId)
            app.decisionLog.log(DecisionEntry(at = DecisionLog.now(), event = "cancel_snooze", alarmId = alarmId))
        }
    }

    /**
     * Відкладення задзвонило: запис і сповіщення більше не потрібні.
     * Повертає, скільки разів будильник уже відкладався сам: дзвінок продовжує цей ланцюжок (FR-21a).
     */
    suspend fun fired(context: Context, alarmId: Long): Int {
        hide(context, alarmId)
        val repository = context.app().settingsRepository
        val autoRepeats = repository.currentPendingSnoozes().firstOrNull { it.alarmId == alarmId }?.autoRepeats ?: 0
        repository.clearPendingSnooze(alarmId)
        return autoRepeats
    }

    /**
     * Ставить збережені відкладення знову. [afterReset] — після перезавантаження чи оновлення:
     * AlarmManager тоді гарантовано порожній, тож відкладення, чий час щойно настав, дзвонить одразу.
     */
    suspend fun restore(context: Context, afterReset: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        val app = context.app()
        for (snooze in app.settingsRepository.currentPendingSnoozes()) {
            val alarm = app.findAlarm(snooze.alarmId)
            val overdue = nowMillis - snooze.ringAtMillis
            if (alarm == null || overdue > STALE_AFTER_MILLIS) {
                Log.i(TAG, "Відкладення не відновлюємо: будильник=${snooze.alarmId}, проґавлено на ${overdue / 1000} с")
                fired(context, snooze.alarmId)
                continue
            }
            if (!afterReset && overdue in 0..FIRING_WINDOW_MILLIS) continue
            // Час, що минув, AlarmManager виконає одразу — дзвонимо із запізненням, а не мовчимо.
            AlarmScheduler(context).snoozeAt(snooze.alarmId, snooze.ringAtMillis)
            if (overdue < 0) show(context, snooze, alarm)
        }
    }

    /** Тихе сповіщення «Відкладено до 15:46» з кнопкою «Скасувати» — видно й поза застосунком. */
    private fun show(context: Context, snooze: PendingSnooze, alarm: Alarm?) {
        val text = if (snooze.alarmId == OneShot.ONE_SHOT_ID || alarm == null) {
            context.getString(R.string.snooze_notif_text_one_shot)
        } else {
            context.getString(R.string.snooze_notif_text_alarm, formatTime(alarm.hour, alarm.minute))
        }
        val id = Notifications.snoozeNotificationId(snooze.alarmId)
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, SnoozeCancelReceiver::class.java).putExtra(SnoozeCancelReceiver.EXTRA_ALARM_ID, snooze.alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_SNOOZE)
            .setSmallIcon(R.drawable.ic_stat_vidbiy)
            .setContentTitle(context.getString(R.string.snooze_notif_title, formatClock(snooze.ringAtMillis)))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            // Зворотний відлік до дзвінка: «9:41» замість часу публікації.
            .setWhen(snooze.ringAtMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.snooze_cancel), cancel)
            .build()
        // Без дозволу на сповіщення система просто нічого не покаже; відкладення від цього не зникне.
        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }

    private fun hide(context: Context, alarmId: Long) {
        context.getSystemService(NotificationManager::class.java)?.cancel(Notifications.snoozeNotificationId(alarmId))
    }

    private fun Context.app() = applicationContext as VidbiyApplication
}

/** Кнопка «Скасувати» у сповіщенні відкладення: скасовує одразу, без підтвердження. */
class SnoozeCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID)
        if (alarmId == Alarm.NEW_ID) return
        val pendingResult = goAsync()
        Snoozes.cancel(context, alarmId).invokeOnCompletion { pendingResult.finish() }
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}
