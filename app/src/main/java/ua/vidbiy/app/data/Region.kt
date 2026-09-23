package ua.vidbiy.app.data

import kotlinx.serialization.Serializable

/**
 * Довідник регіонів із assets/regions.json. Формат збігається з тим, що пише
 * tools/build-regions.mjs із таблиці UID від alerts.in.ua.
 */
@Serializable
data class RegionsAsset(val oblasts: List<Oblast>)

@Serializable
data class Oblast(val uid: String, val title: String, val raions: List<Raion> = emptyList())

@Serializable
data class Raion(val uid: String, val title: String, val hromadas: List<Hromada> = emptyList())

@Serializable
data class Hromada(val uid: String, val title: String)

/**
 * Регіон, який обрав користувач.
 *
 * [coveringUids] — UID усіх регіонів, тривога в яких накриває обраний:
 * для громади це вона сама, її район і її область; для району — він і область;
 * для області — лише вона. Тривоги в сусідніх громадах не враховуються (див. requirements.md).
 *
 * Список зберігається разом із вибором, а не рахується щоразу з довідника:
 * у момент дзвінка потрібне миттєве рішення, без розбору 200-кілобайтного JSON.
 */
@Serializable
data class SelectedRegion(
    val uid: String,
    val title: String,
    /** Де цей регіон розташований, для підпису в інтерфейсі. Порожній рядок для області. */
    val path: String = "",
    val coveringUids: Set<String>,
)

fun Oblast.toSelection() = SelectedRegion(uid = uid, title = title, coveringUids = setOf(uid))

fun Raion.toSelection(oblast: Oblast) = SelectedRegion(
    uid = uid,
    title = title,
    path = oblast.title,
    coveringUids = setOf(uid, oblast.uid),
)

fun Hromada.toSelection(oblast: Oblast, raion: Raion) = SelectedRegion(
    uid = uid,
    title = title,
    path = "${oblast.title} · ${raion.title}",
    coveringUids = setOf(uid, raion.uid, oblast.uid),
)
