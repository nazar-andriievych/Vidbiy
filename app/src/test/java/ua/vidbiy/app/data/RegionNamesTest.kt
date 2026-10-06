package ua.vidbiy.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionNamesTest {

    private val kyivOblast = Oblast("14", "Київська область")
    private val obukhiv = Raion("110", "Обухівський район")
    private val kozyn = Hromada("1500", "Козинська територіальна громада")

    @Test
    fun `підпис громади — від вужчого до ширшого, скорочено`() {
        assertEquals(
            "Козинська громада · Обухівський р-н · Київська обл.",
            kozyn.toSelection(kyivOblast, obukhiv).label,
        )
    }

    @Test
    fun `підпис району`() {
        assertEquals("Обухівський р-н · Київська обл.", obukhiv.toSelection(kyivOblast).label)
    }

    @Test
    fun `місто без районів зберігає назву`() {
        assertEquals("м. Київ", Oblast("31", "м. Київ").toSelection().label)
    }

    @Test
    fun `у списку областей слово «область» прибирається`() {
        assertEquals("Київська", RegionNames.inOblastList("Київська область"))
        assertEquals("м. Київ", RegionNames.inOblastList("м. Київ"))
    }

    @Test
    fun `спершу столиця, окупований Крим і Севастополь — останні`() {
        val ordered = RegionNames.oblastOrder(
            listOf(
                Oblast("29", "Автономна Республіка Крим"),
                Oblast("4", "Вінницька область"),
                Oblast("31", "м. Київ"),
                Oblast("30", "м. Севастополь"),
                Oblast("5", "Волинська область"),
            )
        ).map { it.title }
        assertEquals(
            listOf("м. Київ", "Вінницька область", "Волинська область", "Автономна Республіка Крим", "м. Севастополь"),
            ordered,
        )
    }
}
