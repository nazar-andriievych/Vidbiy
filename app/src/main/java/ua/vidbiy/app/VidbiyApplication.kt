package ua.vidbiy.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.vidbiy.app.alarm.AlarmScheduler
import ua.vidbiy.app.alarm.Notifications
import ua.vidbiy.app.alarm.OneShot
import ua.vidbiy.app.data.Alarm
import ua.vidbiy.app.data.AlarmsRepository
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
    lateinit var dataReady: Deferred<Unit>
        private set

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
        dataReady = applicationScope.async {
            runCatching { LegacyMigration.run(settingsRepository, placesRepository, alarmsRepository) }
            Unit
        }

        // Перестраховка: спрацювання могли загубитися (примусова зупинка застосунку,
        // очищення даних виробником, збій після оновлення). Перезапис уже наявного
        // спрацювання нічого не ламає, тож робимо це на кожному старті.
        applicationScope.launch {
            dataReady.await()
            alarmScheduler.scheduleAll(alarmsRepository.disableMissed())
        }
    }
}
