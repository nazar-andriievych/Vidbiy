package ua.vidbiy.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

/** Результат читання списку: що вдалося розібрати й скільки елементів довелося викинути. */
internal data class LenientList<T>(val items: List<T>, val dropped: Int)

/**
 * Читає JSON-масив по одному елементу: нечитабельний елемент пропадає сам, а решта лишаються.
 * Звичайний `decodeFromString<List<T>>` через один поганий елемент втратив би весь список.
 * Якщо нечитабельний увесь рядок (не JSON, не масив) — порожній список і `dropped = 1`.
 */
internal inline fun <reified T> Json.decodeListLenient(raw: String?): LenientList<T> {
    if (raw.isNullOrBlank()) return LenientList(emptyList(), 0)
    val elements = try {
        parseToJsonElement(raw).jsonArray
    } catch (e: Exception) {
        return LenientList(emptyList(), 1)
    }
    val items = elements.mapNotNull { runCatching { decodeFromJsonElement<T>(it) }.getOrNull() }
    return LenientList(items, elements.size - items.size)
}
