package ua.vidbiy.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LenientDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Зразок збереженого будильника з мінімумом полів (так виглядав би запис зі старої версії).
     * Якщо тест падає, нова версія `Alarm` перестала читати вже збережені будильники:
     * кожне нове поле має мати значення за замовчуванням.
     */
    private val oldAlarm = """{"id":1,"hour":7,"minute":30}"""

    @Test
    fun `старий мінімальний будильник читається зі значеннями за замовчуванням`() {
        val read = json.decodeListLenient<Alarm>("[$oldAlarm]")
        assertEquals(0, read.dropped)
        assertEquals(Alarm(id = 1, hour = 7, minute = 30), read.items.single())
    }

    @Test
    fun `незнайомі поля з новішої версії ігноруються`() {
        val read = json.decodeListLenient<Alarm>("""[{"id":1,"hour":7,"minute":30,"future":true}]""")
        assertEquals(0, read.dropped)
        assertEquals(1, read.items.size)
    }

    @Test
    fun `зіпсований елемент викидається, решта лишається`() {
        val bad = """{"id":2,"minute":0}""" // немає обов'язкового hour
        val read = json.decodeListLenient<Alarm>("[$oldAlarm,$bad]")
        assertEquals(1, read.dropped)
        assertEquals(listOf(1L), read.items.map { it.id })
    }

    @Test
    fun `сміття замість JSON — порожній список і одна втрата`() {
        val read = json.decodeListLenient<Alarm>("{not json")
        assertEquals(0, read.items.size)
        assertEquals(1, read.dropped)
    }

    @Test
    fun `JSON, що не є масивом, — одна втрата`() {
        val read = json.decodeListLenient<Alarm>("""{"id":1}""")
        assertEquals(0, read.items.size)
        assertEquals(1, read.dropped)
    }

    @Test
    fun `порожнє сховище — порожній список без втрат`() {
        assertEquals(LenientList<Alarm>(emptyList(), 0), json.decodeListLenient<Alarm>(null))
        assertEquals(LenientList<Alarm>(emptyList(), 0), json.decodeListLenient<Alarm>("  "))
    }
}
