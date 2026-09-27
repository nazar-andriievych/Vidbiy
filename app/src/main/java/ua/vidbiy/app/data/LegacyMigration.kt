package ua.vidbiy.app.data

/**
 * Перенесення даних зі старих версій. До «Моїх місць» регіон був один на весь застосунок;
 * тепер він живе в кожному будильнику (FR-3). Старий регіон стає основним місцем «Дім»,
 * а будильники без регіону отримують його — тож після оновлення вони поводяться як раніше.
 */
object LegacyMigration {
    const val DEFAULT_PLACE_NAME = "Дім"

    suspend fun run(settings: SettingsRepository, places: PlacesRepository, alarms: AlarmsRepository) {
        val legacy = settings.legacyRegion() ?: return
        val place = places.current().places.firstOrNull { it.region.uid == legacy.uid }
            ?: places.add(DEFAULT_PLACE_NAME, legacy)
        alarms.updateWhere({ it.region == null }) { it.copy(region = place.region, placeId = place.id) }
        settings.clearLegacyRegion()
    }
}
