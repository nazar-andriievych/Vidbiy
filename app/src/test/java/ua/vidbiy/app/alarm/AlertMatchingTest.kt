package ua.vidbiy.app.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.vidbiy.app.data.Hromada
import ua.vidbiy.app.data.Oblast
import ua.vidbiy.app.data.Raion
import ua.vidbiy.app.data.toSelection

class AlertMatchingTest {

    private val hromada = Hromada(uid = "225", title = "м. Луцьк та Луцька територіальна громада")
    private val neighbourHromada = Hromada(uid = "218", title = "Боратинська територіальна громада")
    private val raion = Raion(uid = "39", title = "Луцький район", hromadas = listOf(hromada, neighbourHromada))
    private val otherRaion = Raion(uid = "40", title = "Ковельський район")
    private val oblast = Oblast(uid = "8", title = "Волинська область", raions = listOf(raion, otherRaion))

    @Test
    fun `тривога в самій громаді накриває громаду`() {
        assertTrue(hromada.toSelection(oblast, raion).isUnderAlert(listOf("225")))
    }

    @Test
    fun `тривога в районі накриває його громаду`() {
        assertTrue(hromada.toSelection(oblast, raion).isUnderAlert(listOf("39")))
    }

    @Test
    fun `тривога в області накриває її громаду`() {
        assertTrue(hromada.toSelection(oblast, raion).isUnderAlert(listOf("8")))
    }

    @Test
    fun `тривога в сусідній громаді не накриває`() {
        assertFalse(hromada.toSelection(oblast, raion).isUnderAlert(listOf("218")))
    }

    @Test
    fun `тривога в сусідньому районі не накриває`() {
        assertFalse(raion.toSelection(oblast).isUnderAlert(listOf("40")))
    }

    @Test
    fun `тривога в громаді не накриває всю область`() {
        assertFalse(oblast.toSelection().isUnderAlert(listOf("225", "39")))
    }

    @Test
    fun `тривога в області накриває обрану область`() {
        assertTrue(oblast.toSelection().isUnderAlert(listOf("16", "8")))
    }

    @Test
    fun `порожній список тривог нікого не накриває`() {
        assertFalse(hromada.toSelection(oblast, raion).isUnderAlert(emptyList()))
    }
}
