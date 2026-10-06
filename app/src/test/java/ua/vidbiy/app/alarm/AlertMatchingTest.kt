package ua.vidbiy.app.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.vidbiy.app.data.Hromada
import ua.vidbiy.app.data.Oblast
import ua.vidbiy.app.data.Raion
import ua.vidbiy.app.data.SelectedRegion
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

    // FR-29: Харків і Запоріжжя ukrainealarm веде окремо від їхнього району.
    private val kharkiv = Hromada(uid = "1293", title = "м. Харків та Харківська територіальна громада")
    private val kharkivRaion = Raion(uid = "124", title = "Харківський район", hromadas = listOf(kharkiv))
    private val kharkivOblast = Oblast(uid = "22", title = "Харківська область", raions = listOf(kharkivRaion))

    @Test
    fun `тривога району не накриває Харків`() {
        assertFalse(kharkiv.toSelection(kharkivOblast, kharkivRaion).isUnderAlert(listOf("124")))
    }

    @Test
    fun `тривога в самому Харкові і в області його накриває`() {
        val selection = kharkiv.toSelection(kharkivOblast, kharkivRaion)
        assertTrue(selection.isUnderAlert(listOf("1293")))
        assertTrue(selection.isUnderAlert(listOf("22")))
    }

    @Test
    fun `збережений до виправлення Запоріжжя з районом теж не чекає тривоги району`() {
        val saved = SelectedRegion(uid = "564", title = "м. Запоріжжя", coveringUids = setOf("564", "149", "12"))
        assertFalse(saved.isUnderAlert(listOf("149")))
        assertTrue(saved.isUnderAlert(listOf("564")))
        assertTrue(saved.isUnderAlert(listOf("12")))
    }
}
