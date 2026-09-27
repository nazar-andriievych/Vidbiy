package ua.vidbiy.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.vidbiy.app.alarm.AlarmScheduler
import ua.vidbiy.app.alarm.Notifications
import ua.vidbiy.app.data.AlarmsRepository
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

    /** Живе стільки ж, скільки процес: сюди йде робота, яку не можна кидати посеред шляху. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Перенесення даних старих версій. Приймачі будильника чекають на нього, перш ніж
     * читати будильники: інакше в першу хвилину після оновлення будильник міг би
     * побачити себе без регіону й задзвонити як звичайний.
     */
    lateinit var dataReady: Deferred<Unit>
        private set

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
            alarmScheduler.scheduleAll(alarmsRepository.alarms.first())
        }
    }
}
