package ua.vidbiy.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Довідник регіонів. Лежить у assets статичним файлом: список громад змінюється раз на роки,
 * а от працювати без мережі застосунок має завжди.
 */
class RegionsCatalog(val oblasts: List<Oblast>) {

    /** Пошук по всіх рівнях одразу: людина частіше знає назву своєї громади, ніж району. */
    fun search(query: String, limit: Int = 60): List<SelectedRegion> {
        val needle = query.trim().withPlainApostrophes()
        if (needle.isEmpty()) return emptyList()

        val found = mutableListOf<SelectedRegion>()
        for (oblast in oblasts) {
            if (oblast.title.matches(needle)) found += oblast.toSelection()
            for (raion in oblast.raions) {
                if (raion.title.matches(needle)) found += raion.toSelection(oblast)
                for (hromada in raion.hromadas) {
                    if (hromada.title.matches(needle)) found += hromada.toSelection(oblast, raion)
                    if (found.size >= limit) return found
                }
            }
        }
        return found
    }

    private fun String.matches(needle: String) = withPlainApostrophes().contains(needle, ignoreCase = true)

    /**
     * У назвах ukrainealarm трапляються і ', і ’, а клавіатура телефона ставить будь-який з них
     * (або ʼ). Для пошуку вони однакові: «Кам'янський» має знаходити «Кам’янський».
     */
    private fun String.withPlainApostrophes() = replace('’', '\'').replace('ʼ', '\'').replace('`', '\'')

    companion object {
        private const val ASSET_NAME = "regions.json"
        private val json = Json { ignoreUnknownKeys = true }
        private val mutex = Mutex()
        private var cached: RegionsCatalog? = null

        /** Читання й розбір ~200 КБ JSON — не для головного потоку, тож suspend і кеш. */
        suspend fun load(context: Context): RegionsCatalog = mutex.withLock {
            cached ?: withContext(Dispatchers.IO) {
                val raw = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                RegionsCatalog(json.decodeFromString<RegionsAsset>(raw).oblasts)
            }.also { cached = it }
        }
    }
}
