package ua.vidbiy.app.alarm

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.vidbiy.app.BuildConfig
import ua.vidbiy.app.MainActivity
import ua.vidbiy.app.R
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import ua.vidbiy.app.VidbiyApplication
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.DecisionEntry
import ua.vidbiy.app.data.DecisionLog
import ua.vidbiy.app.data.SettingsRepository
import ua.vidbiy.app.ui.AlarmRingActivity
import ua.vidbiy.app.ui.formatTime

/**
 * Дзвінок будильника: програє мелодію, вібрує й тримає повноекранну нотифікацію.
 *
 * Це foreground service — служба з постійною нотифікацією. Такій службі система не дає
 * померти, поки вона працює; звичайний фоновий код Android прибив би за секунди.
 */
class AlarmRingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null

    /** Мелодії, які ще можна спробувати, якщо поточна не грає. */
    private val soundQueue = ArrayDeque<Uri>()

    // USAGE_ALARM — той самий потік, що й у системного будильника:
    // його чути в «Не турбувати» й керує ним гучність будильника, а не медіа.
    private val soundAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Фокус аудіо: просимо інші застосунки (музика, подкаст, білий шум) замовкнути на час дзвінка,
     * а після вимкнення — продовжити. TRANSIENT означає «ненадовго», тому вони стають на паузу,
     * а не зупиняються зовсім. Відмова чи втрата фокуса дзвінок не зупиняє: будильник грає все одно.
     */
    private var focusRequest: AudioFocusRequest? = null

    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoStopJob: Job? = null

    /** Будильники, що дзвонять зараз: дзвінок один, а екран і кнопки — на всіх разом. */
    private val ringing = LinkedHashMap<Long, RingEntry>()
    private var snoozeMinutes: Int = SettingsRepository.DEFAULT_SNOOZE_MINUTES

    /** Запис «служба не на передньому плані» — один на дзвінок, а не на кожен будильник у ньому. */
    private var fallbackLogged = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRinging(intent)
            ACTION_SNOOZE -> {
                // Відкладення, як і вимкнення, стосується всіх будильників, що дзвонять.
                val ids = ringing.keys.toList().ifEmpty { listOf(intent.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID)) }
                ids.forEach { Snoozes.snooze(this, alarmId = it, minutes = snoozeMinutes) }
                stopEverything()
            }

            ACTION_DISMISS -> {
                // Людина вимкнула дзвінок — страховка автовідкладення більше не потрібна.
                val scheduler = AlarmScheduler(this)
                ringing.keys.forEach { scheduler.cancelSnooze(it) }
                stopEverything()
            }
            else -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun startRinging(intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID)
        val hour = intent.getIntExtra(EXTRA_HOUR, 0)
        val minute = intent.getIntExtra(EXTRA_MINUTE, 0)
        val vibrate = intent.getBooleanExtra(EXTRA_VIBRATE, true)
        val ringtoneUri = intent.getStringExtra(EXTRA_RINGTONE_URI)
        val reasonJson = intent.getStringExtra(EXTRA_REASON)
        val reason = reasonJson?.let { runCatching { json.decodeFromString<RingReason>(it) }.getOrNull() } ?: RingReason.Plain
        snoozeMinutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, SettingsRepository.DEFAULT_SNOOZE_MINUTES)
        val autoRepeats = intent.getIntExtra(EXTRA_AUTO_REPEATS, 0)

        // Другий будильник (той самий регіон закінчився одночасно, або наспів інший час) долучається
        // до дзвінка, що вже йде: звук один, а на екрані видно всі. Той самий будильник двічі
        // (скажімо, відкладення, що збіглося з дзвінком) просто оновлює свій запис.
        val alreadyRinging = ringing.isNotEmpty()
        ringing[alarmId] = RingEntry(alarmId, hour, minute, reason, autoRepeats)
        RingState.set(ringing.values.toList(), snoozeMinutes)
        // Будильник дзвонить знову — давнє «Пропущений будильник» уже нічого не каже.
        notificationManager()?.cancel(Notifications.missedNotificationId(alarmId))
        armAutoSnoozeBackstop(alarmId, autoRepeats)

        startForegroundNotification(alarmId, hour, minute, reason, reasonJson)
        if (!alreadyRinging) {
            acquireWakeLock()
            startSound(ringtoneUri)
            if ((application as VidbiyApplication).isVisible) openRingScreen(alarmId, hour, minute, reasonJson)
        }
        if (vibrate && vibrator == null) startVibration()

        // Будильник, який дзвонить годинами, розряджає телефон і дратує сусідів.
        // Через 10 хвилин замовкаємо, але не назавжди: відкладаємося самі (FR-21a).
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(RING_MILLIS)
            onRingTimeout()
        }
    }

    /**
     * Автовідкладення нижче живе в пам'яті цієї служби. Якщо систему чи збій її вб'є посеред
     * дзвінка, будильник замовк би без сліду, тож одразу ставимо в AlarmManager той самий дзвінок,
     * що дало б автовідкладення: «зараз + дзвінок + X хв». Звичайне автовідкладення, ручне
     * «Відкласти» і «Скасувати» замінюють чи прибирають його (той самий PendingIntent), «Вимкнути» —
     * скасовує. Запису на диску в страховки немає: у списку вона не показується, а лічильник
     * повторів після неї починається з нуля — у бік зайвого дзвінка, не тиші.
     * Третій дзвінок (далі лише «Пропущений будильник») страховки не має.
     */
    private fun armAutoSnoozeBackstop(alarmId: Long, autoRepeats: Int) {
        if (ringTimeout(autoRepeats) !is RingTimeout.AutoSnooze) return
        AlarmScheduler(this).snoozeAt(alarmId, System.currentTimeMillis() + RING_MILLIS + snoozeMinutes * 60_000L)
    }

    /**
     * Дзвінок ніхто не вимкнув і не відклав. Не почула його саме та людина, яку треба розбудити,
     * тож кожен будильник відкладає себе сам — до [MAX_AUTO_SNOOZES] разів, а далі лишає
     * сповіщення «Пропущений будильник», щоб пропуск не зник безслідно.
     */
    private fun onRingTimeout() {
        val app = application as VidbiyApplication
        for (entry in ringing.values) {
            val next = ringTimeout(entry.autoRepeats)
            when (next) {
                is RingTimeout.AutoSnooze ->
                    Snoozes.snooze(this, alarmId = entry.alarmId, minutes = snoozeMinutes, autoRepeats = next.autoRepeats)

                RingTimeout.GiveUp -> showMissed(entry)
            }
            Log.i(TAG, "Дзвінок без відповіді: будильник=${entry.alarmId}, повторів=${entry.autoRepeats} → $next")
            app.applicationScope.launch {
                app.decisionLog.log(
                    DecisionEntry(
                        at = DecisionLog.now(),
                        event = if (next is RingTimeout.AutoSnooze) "auto_snooze" else "missed",
                        alarmId = entry.alarmId,
                        note = "повтор ${entry.autoRepeats} з $MAX_AUTO_SNOOZES, відкладення $snoozeMinutes хв",
                    ),
                )
            }
        }
        stopEverything()
    }

    /** Звичайне (не постійне) сповіщення: висить, поки людина його не змахне чи будильник не задзвонить знову. */
    private fun showMissed(entry: RingEntry) {
        val id = Notifications.missedNotificationId(entry.alarmId)
        val open = PendingIntent.getActivity(
            this,
            id,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val totalMinutes = RING_MILLIS / 60_000L * (MAX_AUTO_SNOOZES + 1)
        val label = ringLabel(entry.hour, entry.minute, entry.reason)
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.missed_title))
            .setContentText(label)
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.missed_text, label, totalMinutes)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        notificationManager()?.notify(id, notification)
    }

    private fun notificationManager(): NotificationManager? = getSystemService(NotificationManager::class.java)

    /**
     * Повноекранний інтент на розблокованому телефоні, яким користуються, система замінює
     * спливним сповіщенням. Поки застосунок на екрані, відкриваємо екран дзвінка самі.
     * Не вийшло — лишається сповіщення: звук від цього не зупиниться.
     */
    private fun openRingScreen(alarmId: Long, hour: Int, minute: Int, reasonJson: String?) {
        runCatching { startActivity(AlarmRingActivity.intent(this, alarmId, hour, minute, reasonJson, snoozeMinutes)) }
            .onFailure { Log.w(TAG, "Не вдалося відкрити екран дзвінка", it) }
    }

    private fun startForegroundNotification(alarmId: Long, hour: Int, minute: Int, reason: RingReason, reasonJson: String?) {
        // Один код запиту: повторний показ оновлює той самий повноекранний інтент, а не множить їх.
        val fullScreen = PendingIntent.getActivity(
            this,
            0,
            AlarmRingActivity.intent(this, alarmId, hour, minute, reasonJson, snoozeMinutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.ring_title))
            .setContentText(
                if (ringing.size > 1) {
                    getString(R.string.ring_text_multiple, ringing.size)
                } else {
                    ringLabel(hour, minute, reason)
                },
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, getString(R.string.ring_snooze, snoozeMinutes), servicePendingIntent(ACTION_SNOOZE, alarmId))
            .addAction(0, getString(R.string.ring_dismiss), servicePendingIntent(ACTION_DISMISS, alarmId))
            .build()

        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Notifications.ALARM_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(Notifications.ALARM_NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "Служба дзвінка не вийшла на передній план", it) }.isSuccess
        // Обмеженому у фоні застосунку (Samsung «глибокий сон») система мовчки відмовляє:
        // без винятку, але й без сповіщення — звук іде, а вимкнути нічим. Тоді показуємо те саме
        // сповіщення звичайним способом: його кнопки й повноекранний інтент працюють і так.
        val promoted = started && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || foregroundServiceType != 0)
        if (!promoted) {
            notificationManager()?.notify(Notifications.ALARM_NOTIFICATION_ID, notification)
            if (!fallbackLogged) {
                fallbackLogged = true
                val app = application as VidbiyApplication
                app.applicationScope.launch {
                    app.decisionLog.log(
                        DecisionEntry(at = DecisionLog.now(), event = "ring_not_foreground", alarmId = alarmId),
                    )
                }
            }
        }
    }

    /**
     * Той самий підпис, що вгорі екрана дзвінка: «Будильник 06:45 · Дім» або «Розбуди після відбою · Дім».
     * У разового режиму час — момент увімкнення, тож «Будильник на 22:56» вводив би в оману.
     */
    private fun ringLabel(hour: Int, minute: Int, reason: RingReason): String {
        val time = formatTime(hour, minute)
        return when {
            reason.oneShot -> getString(R.string.ring_label_one_shot, reason.placeName.orEmpty())
            reason.placeName != null -> getString(R.string.ring_label, time, reason.placeName)
            else -> getString(R.string.ring_label_no_place, time)
        }
    }

    private fun servicePendingIntent(action: String, alarmId: Long): PendingIntent {
        val intent = Intent(this, AlarmRingService::class.java).apply {
            this.action = action
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getService(
            this,
            action.hashCode() + alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Обрану мелодію могли видалити або вона могла виявитися недоступною.
     * Мовчазний будильник — найгірший зі сценаріїв, тож пробуємо по черзі: обрану, типову мелодію
     * будильника, рінгтон дзвінка і нарешті власний звук з APK — його неможливо «не знайти».
     */
    private fun startSound(ringtoneUri: String?) {
        requestAudioFocus()
        soundQueue.clear()
        soundQueue += listOfNotNull(
            ringtoneUri?.let(Uri::parse),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            Uri.parse("android.resource://$packageName/${R.raw.alarm_fallback}"),
        ).distinct()
        playNextSound()
    }

    private fun requestAudioFocus() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(soundAttributes)
            .build()
        focusRequest = request
        runCatching { audio.requestAudioFocus(request) }
            .onFailure { Log.w(TAG, "Не вдалося отримати фокус аудіо", it) }
    }

    private fun abandonAudioFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    private fun playNextSound() {
        while (soundQueue.isNotEmpty()) {
            player = play(soundQueue.removeFirst()) ?: continue
            return
        }
        Log.e(TAG, "Жодна мелодія не грає — лишається тільки вібрація")
    }

    private fun play(uri: Uri): MediaPlayer? {
        val player = MediaPlayer()
        return runCatching {
            player.apply {
                setAudioAttributes(soundAttributes)
                setDataSource(this@AlarmRingService, uri)
                isLooping = true
                // Файл може відкритися, а зламатися вже під час програвання (битий кінець файлу,
                // збій декодера). Без цього обробника звук тихо зник би, а екран дзвонив би далі.
                setOnErrorListener { failed, what, extra ->
                    Log.w(TAG, "Мелодія $uri обірвалася: what=$what extra=$extra")
                    failed.release()
                    if (this@AlarmRingService.player === failed) {
                        this@AlarmRingService.player = null
                        playNextSound()
                    }
                    true
                }
                prepare()
                start()
            }
        }.onFailure {
            Log.w(TAG, "Не вдалося програти мелодію $uri", it)
            player.release()
        }.getOrNull()
    }

    private fun startVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return

        this.vibrator = vibrator
        val pattern = longArrayOf(0, 700, 800)
        vibrator.vibrate(
            VibrationEffect.createWaveform(pattern, 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
        )
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(RING_MILLIS + 60_000L)
        }
    }

    private fun stopEverything() {
        autoStopJob?.cancel()
        soundQueue.clear()
        runCatching { player?.stop() }
        player?.release()
        player = null
        abandonAudioFocus()
        vibrator?.cancel()
        vibrator = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ringing.clear()
        RingState.set(emptyList())
        fallbackLogged = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Якщо служба так і не вийшла на передній план, сповіщення висить окремо від неї.
        notificationManager()?.cancel(Notifications.ALARM_NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmRingService"
        private const val WAKE_LOCK_TAG = "vidbiy:ring"

        /** Скільки дзвонить один дзвінок. У debug можна скоротити: `-Pvidbiy.ringMinutes=1`. */
        private val RING_MILLIS = BuildConfig.RING_MINUTES * 60_000L

        const val ACTION_START = "ua.vidbiy.app.action.START_RINGING"
        const val ACTION_SNOOZE = "ua.vidbiy.app.action.SNOOZE"
        const val ACTION_DISMISS = "ua.vidbiy.app.action.DISMISS"

        const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_HOUR = "hour"
        private const val EXTRA_MINUTE = "minute"
        private const val EXTRA_VIBRATE = "vibrate"
        private const val EXTRA_RINGTONE_URI = "ringtone_uri"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        private const val EXTRA_AUTO_REPEATS = "auto_repeats"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Почати дзвінок. [reason] показується на екрані дзвінка (FR-21); тривалість
         * відкладення (FR-19) читаємо тут, щоб кнопки й сповіщення знали її одразу.
         */
        suspend fun startRinging(
            context: Context,
            alarm: Alarm,
            reason: RingReason = RingReason.Plain,
            /** Скільки разів цей дзвінок уже відкладався сам (FR-21a); 0 — звичайний дзвінок. */
            autoRepeats: Int = 0,
        ) {
            val app = context.applicationContext as VidbiyApplication
            val snooze = app.settingsRepository.snoozeMinutes.first()

            startOrDefer(
                context,
                startIntent(context, alarm.id, alarm.hour, alarm.minute, alarm.vibrate, alarm.ringtoneUri, reason, snooze, autoRepeats),
            )
            // Дзвінок і очікування відбою одного будильника взаємно виключні. Очікування зупиняємо лише тепер,
            // коли дзвінок уже запущено: часто нас викликає саме служба очікування, і її зупинка
            // скасовує цю корутину. Зупинка до запуску обривала дзвінок на першому ж `first()`
            // вище — будильник мовчки не дзвонив після відбою (журнал рішень, 2026-09-29).
            // Після `startForegroundService` пауз немає, тож скасуванню тут нема чого обірвати.
            // Зупиняємо лише очікування цього будильника: решта (інші регіони) чекають далі.
            AlarmWaitService.stop(context, alarm.id)
        }

        /**
         * Дзвінок після перезавантаження, поки телефон не розблокували ([LockedBoot]): налаштувань
         * не прочитати, тож без перевірки тривоги й типовим звуком будильника (мелодію не знаємо).
         */
        fun startRingingLocked(context: Context, fire: LockedFire, snoozeMinutes: Int) {
            val oneShot = fire.alarmId == OneShot.ONE_SHOT_ID
            val reason = when {
                // Відкладений дзвінок дзвонить незалежно від тривоги (FR-20) — як і завжди, без причини.
                fire.kind == LockedFire.Kind.SNOOZE || !fire.respectAlerts -> RingReason.Plain
                else -> RingReason(RingReason.Kind.LOCKED_BOOT)
            }.copy(oneShot = oneShot)
            startOrDefer(
                context,
                startIntent(context, fire.alarmId, fire.hour, fire.minute, fire.vibrate, null, reason, snoozeMinutes, fire.autoRepeats),
            )
        }

        /**
         * Запускає службу дзвінка. Android дозволяє це з фону лише в особливих випадках (зокрема
         * ~10 с після будильникового таймера); служба очікування, яку система перестала вважати
         * службою переднього плану (Samsung «глибокий сон»), отримує ForegroundServiceStartNotAllowedException —
         * і будильник мовчав би. Тоді ставимо будильниковий таймер на «зараз»: він запускає ту саму
         * службу вже у своєму дозволеному вікні.
         */
        private fun startOrDefer(context: Context, intent: Intent) {
            val failure = runCatching { context.startForegroundService(intent) }.exceptionOrNull() ?: return
            Log.w(TAG, "Службу дзвінка не дали запустити — дзвонимо через будильниковий таймер", failure)
            AlarmScheduler(context).ringSoon(intent.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID), intent)
            val app = context.applicationContext as VidbiyApplication
            app.applicationScope.launch {
                app.decisionLog.log(
                    DecisionEntry(
                        at = DecisionLog.now(),
                        event = "ring_deferred",
                        alarmId = intent.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID),
                        note = failure.javaClass.simpleName,
                    ),
                )
            }
        }

        private fun startIntent(
            context: Context,
            alarmId: Long,
            hour: Int,
            minute: Int,
            vibrate: Boolean,
            ringtoneUri: String?,
            reason: RingReason,
            snoozeMinutes: Int,
            autoRepeats: Int,
        ): Intent = Intent(context, AlarmRingService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
            putExtra(EXTRA_VIBRATE, vibrate)
            putExtra(EXTRA_RINGTONE_URI, ringtoneUri)
            putExtra(EXTRA_REASON, json.encodeToString(reason))
            putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            putExtra(EXTRA_AUTO_REPEATS, autoRepeats)
        }

        fun snoozeIntent(context: Context, alarmId: Long): Intent =
            Intent(context, AlarmRingService::class.java).apply {
                action = ACTION_SNOOZE
                putExtra(EXTRA_ALARM_ID, alarmId)
            }

        fun dismissIntent(context: Context, alarmId: Long): Intent =
            Intent(context, AlarmRingService::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
    }
}
