package ua.vidbiy.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Справжній довідник з assets — інваріанти, на які спирається пошук і правила покриття.
 * Довідник будується зі списку регіонів ukrainealarm (tools/build-regions.mjs).
 */
class RegionsCatalogTest {

    /** Gradle запускає тести з каталогу модуля `app/`. */
    private val catalog = RegionsCatalog(
        Json { ignoreUnknownKeys = true }
            .decodeFromString<RegionsAsset>(File("src/main/assets/regions.json").readText())
            .oblasts,
    )

    private val allUids = catalog.oblasts.flatMap { o ->
        listOf(o.uid) + o.raions.flatMap { r -> listOf(r.uid) + r.hromadas.map { it.uid } }
    }

    @Test
    fun `UID регіонів не повторюються`() {
        val duplicates = allUids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("Повтори: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `у районі немає двох громад з однаковою назвою`() {
        val duplicates = catalog.oblasts.flatMap { it.raions }.flatMap { raion ->
            raion.hromadas.groupBy { it.title }.filterValues { it.size > 1 }.keys.map { "${raion.title}: $it" }
        }
        assertTrue("Повтори: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `злиті громади з довідника прибрано, лишилась та, яку веде ukrainealarm (Є3)`() {
        MERGED_UIDS.forEach { (removed, kept) ->
            assertTrue("$removed має бути прибрана", removed !in allUids)
            assertTrue("$kept має лишитися", kept in allUids)
        }
        assertEquals(1, catalog.search("Миколаївська").count { it.path.contains("Сумський") })
    }

    @Test
    fun `окремі міста FR-29 є в довіднику разом зі своєю областю`() {
        SEPARATE_CITIES.forEach { (city, oblast) ->
            assertTrue(city in allUids)
            assertTrue(catalog.oblasts.single { it.uid == oblast }.raions.any { r -> r.hromadas.any { it.uid == city } })
        }
    }

    @Test
    fun `пошук знаходить громаду разом з районом і областю`() {
        val bucha = catalog.search("Бучанська").first { it.uid == "702" }
        assertEquals(setOf("702", "75", "14"), bucha.alertUids)
    }

    @Test
    fun `пошук не розрізняє апострофи`() {
        listOf("Кам'янський район", "Кам’янський район", "Камʼянський район").forEach { query ->
            assertTrue(query, catalog.search(query).any { it.uid == "42" })
        }
    }

    @Test
    fun `чинні назви перейменованих громад, а не ті, що в ukrainealarm`() {
        assertTrue(catalog.search("Шептицький").any { it.uid == "832" })
        assertTrue(catalog.search("Червоноград").isEmpty())
    }

    @Test
    fun `окупованих районів, на які тривог не оголошують, у довіднику немає`() {
        val uids = catalog.oblasts.flatMap { o -> listOf(o.uid) + o.raions.flatMap { r -> listOf(r.uid) + r.hromadas.map { it.uid } } }
        assertTrue(uids.none { it == "30" || it in (1801..1804).map(Int::toString) })
    }
}
