package ua.vidbiy.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.vidbiy.app.alarm.AlarmScheduler
import ua.vidbiy.app.alarm.Notifications
import ua.vidbiy.app.data.AlarmsRepository
import ua.vidbiy.app.data.SettingsRepository

/**
 * Точка, де живуть об'єкти на весь час роботи застосунку — аналог реєстрації синглтонів у DI.
 * Окремої бібліотеки DI поки не заводимо: залежність одна.
 */
class VidbiyApplication : Application() {
    val alarmsRepository: AlarmsRepository by lazy { AlarmsRepository(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)

        // Перестраховка: спрацювання могли загубитися (примусова зупинка застосунку,
        // очищення даних виробником, збій після оновлення). Перезапис уже наявного
        // спрацювання нічого не ламає, тож робимо це на кожному старті.
        scope.launch { alarmScheduler.scheduleAll(alarmsRepository.alarms.first()) }
    }
}
