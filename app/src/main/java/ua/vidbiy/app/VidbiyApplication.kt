package ua.vidbiy.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.vidbiy.app.alarm.AlarmScheduler
import ua.vidbiy.app.alarm.LockedBoot
import ua.vidbiy.app.alarm.lockedBootPlan
import ua.vidbiy.app.alarm.Notifications
import ua.vidbiy.app.alarm.OneShot
import ua.vidbiy.app.alarm.Snoozes
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.AlarmsRepository
import ua.vidbiy.app.data.AlertsClient
import ua.vidbiy.app.data.DecisionLog
import ua.vidbiy.app.data.LegacyMigration
import ua.vidbiy.app.data.PlacesEditor
import ua.vidbiy.app.data.PlacesRepository
import ua.vidbiy.app.data.SettingsRepository

/**
 * Точка, де живуть об'єкти на весь час роботи застосунку — аналог реєстрації синглтонів у DI.
 * Окремої бібліотеки DI поки не заводимо: залежність одна.
 */
class VidbiyApplication : Application() {
    val alarmsRepository: AlarmsRepository by lazy { AlarmsRepository(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler(this) }
    val placesRepository: PlacesRepository by lazy { PlacesRepository(this) }
    val placesEditor: PlacesEditor by lazy { PlacesEditor(placesRepository, alarmsRepository) }
    val decisionLog: DecisionLog by lazy { DecisionLog(this) }

    /** Клієнт проксі, що попутно запам'ятовує останній випуск застосунку (банер оновлення). */
    fun alertsClient(): AlertsClient =
        AlertsClient(settingsRepository.proxyBaseUrl(), onUpdate = settingsRepository::setAppUpdate)

    /**
     * Живе стільки ж, скільки процес: сюди йде робота, яку не можна кидати посеред шляху.
     * Необроблений виняток тут валив би весь процес, а з ним і дзвінок, що саме грає
     * (наприклад, коли диск не дає записати стан), тож лише пишемо його в лог.
     */
    val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e("VidbiyApp", "Необроблений виняток у фоновій роботі", e) },
    )

    /**
     * Перенесення даних старих версій. Приймачі будильника чекають на нього, перш ніж
     * читати будильники: інакше в першу хвилину після оновлення будильник міг би
     * побачити себе без регіону й задзвонити як звичайний.
     */
    val dataReady: Deferred<Unit> get() = _dataReady
    private val _dataReady = CompletableDeferred<Unit>()
    private val unlockedWorkStarted = AtomicBoolean(false)

    /** Скільки екранів застосунку зараз видно (між onStart і onStop). */
    private var startedActivities = 0

    /**
     * Чи відкритий застосунок на екрані. Коли телефон розблокований і ним користуються, Android
     * показує замість повноекранного дзвінка лише спливне сповіщення (а Samsung — підсвітку країв).
     * Поки застосунок видно, служба дзвінка сама відкриває екран дзвінка — це дозволено.
     */
    val isVisible: Boolean get() = startedActivities > 0

    /**
     * Будильник за id, включно з віртуальним будильником разового режиму (OneShot):
     * його немає в сховищі, він збирається з налаштувань режиму й основного місця.
     */
    suspend fun findAlarm(id: Long): Alarm? {
        if (id == OneShot.ONE_SHOT_ID) {
            return OneShot.alarm(
                primary = placesRepository.current().primary,
                waitFor = settingsRepository.oneShotWaitFor.first(),
                pauseMinutes = settingsRepository.oneShotPauseMinutes.first(),
            )
        }
        return alarmsRepository.alarms.first().firstOrNull { it.id == id }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Після перезавантаження процес може стартувати ще до розблокування (LockedBoot):
        // тоді сховища не прочитати, і решту роботи запустить BootReceiver після розблокування.
        if (!LockedBoot.isLocked(this)) onUserUnlocked()
    }

    /**
     * Робота, якій потрібне звичайне сховище. Викликається один раз: зі старту застосунку
     * або, якщо процес стартував до розблокування, з BootReceiver після нього.
     */
    fun onUserUnlocked() {
        if (!unlockedWorkStarted.compareAndSet(false, true)) return
        applicationScope.launch {
            runCatching { LegacyMigration.run(settingsRepository, placesRepository, alarmsRepository) }
            // Що задзвонило до розблокування — у сховище, перш ніж хтось почне ставити будильники.
            runCatching { LockedBoot.handOver(this@VidbiyApplication) }
                .onFailure { Log.e("VidbiyApp", "Не вдалося перенести дзвінки до розблокування", it) }
            _dataReady.complete(Unit)

            // Перестраховка: спрацювання могли загубитися (примусова зупинка застосунку,
            // очищення даних виробником, збій після оновлення). Перезапис уже наявного
            // спрацювання нічого не ламає, тож робимо це на кожному старті.
            alarmScheduler.scheduleAll(alarmsRepository.disableMissed())
            Snoozes.restore(this@VidbiyApplication, afterReset = false)
        }
        // Копія розкладу для перезавантаження без розблокування — на кожну зміну.
        applicationScope.launch {
            dataReady.await()
            combine(
                alarmsRepository.alarms,
                settingsRepository.pendingSnoozes,
                settingsRepository.pendingWaits,
                settingsRepository.snoozeMinutes,
            ) { alarms, snoozes, waits, snoozeMinutes -> lockedBootPlan(alarms, snoozes, waits, snoozeMinutes) }
                .collect { LockedBoot.savePlan(this@VidbiyApplication, it) }
        }
    }
}
