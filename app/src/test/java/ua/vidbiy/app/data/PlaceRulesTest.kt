package ua.vidbiy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-26, FR-26a: «Мої місця» і що їхні зміни роблять з будильниками. */
class PlaceRulesTest {

    private val bucha = SelectedRegion("702", "Буча", coveringUids = setOf("702", "75", "14"))
    private val kyiv = SelectedRegion("31", "м. Київ", coveringUids = setOf("31"))
    private val home = Place(1, "Дім", bucha)
    private val dacha = Place(2, "Дача", kyiv)

    @Test
    fun `назва обрізається до 24 символів і без пробілів по краях`() {
        assertEquals("Дім", PlaceRules.cleanName("  Дім  "))
        assertEquals(24, PlaceRules.cleanName("Дуже довга назва для мого місця").length)
    }

    @Test
    fun `нове місце отримує наступний номер`() {
        assertEquals(1L, PlaceRules.newPlace(emptyList(), "Дім", bucha).id)
        assertEquals(3L, PlaceRules.newPlace(listOf(home, dacha), "Робота", kyiv).id)
    }

    @Test
    fun `перше місце — основне, навіть якщо основне ще не записане`() {
        assertEquals(home, PlacesState(listOf(home)).primary)
    }

    @Test
    fun `основне місце видалити не можна, інше — можна`() {
        val state = PlacesState(listOf(home, dacha), primaryId = 1)
        assertFalse(PlaceRules.canDelete(state, 1))
        assertTrue(PlaceRules.canDelete(state, 2))
    }

    @Test
    fun `основним стало інше — тепер можна видалити перше`() {
        assertTrue(PlaceRules.canDelete(PlacesState(listOf(home, dacha), primaryId = 2), 1))
    }

    @Test
    fun `основне місце показується першим`() {
        assertEquals(listOf(dacha, home), PlacesState(listOf(home, dacha), primaryId = 2).ordered)
    }

    @Test
    fun `зміна регіону місця переводить його будильники на новий регіон`() {
        val alarm = Alarm(id = 5, hour = 7, minute = 0, region = bucha, placeId = 1)
        val other = Alarm(id = 6, hour = 7, minute = 0, region = bucha, placeId = 2)

        assertEquals(kyiv, alarm.withPlaceRegion(1, kyiv).region)
        assertEquals(bucha, other.withPlaceRegion(1, kyiv).region)
    }

    @Test
    fun `видалене місце — будильник зберігає регіон без назви місця`() {
        val alarm = Alarm(id = 5, hour = 7, minute = 0, region = bucha, placeId = 1)

        val after = alarm.withoutPlace(1)

        assertNull(after.placeId)
        assertEquals(bucha, after.region)
    }
}
