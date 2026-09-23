package ua.vidbiy.app

import android.app.Application
import ua.vidbiy.app.data.AlarmsRepository
import ua.vidbiy.app.data.SettingsRepository

/**
 * Точка, де живуть об'єкти на весь час роботи застосунку — аналог реєстрації синглтонів у DI.
 * Окремої бібліотеки DI поки не заводимо: залежність одна.
 */
class VidbiyApplication : Application() {
    val alarmsRepository: AlarmsRepository by lazy { AlarmsRepository(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
}
