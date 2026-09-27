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

/**
 * Короткі назви для інтерфейсу (design-spec 3.3): у довіднику «Обухівський район» і
 * «Козинська територіальна громада», а на екрані — «Обухівський р-н», «Козинська громада».
 */
object RegionNames {
    private const val HROMADA = " територіальна громада"
    private const val RAION = " район"
    private const val OBLAST = " область"

    fun short(title: String): String = when {
        title.endsWith(HROMADA) -> title.removeSuffix(HROMADA) + " громада"
        title.endsWith(RAION) -> title.removeSuffix(RAION) + " р-н"
        title.endsWith(OBLAST) -> title.removeSuffix(OBLAST) + " обл."
        else -> title
    }

    /** У списку областей слово «область» зайве: «Вінницька», «м. Київ». */
    fun inOblastList(title: String): String = title.removeSuffix(OBLAST)

    /**
     * Порядок областей у списку: столиця першою, тимчасово окуповані Крим і Севастополь
     * останніми, решта — як у довіднику (за абеткою).
     */
    fun oblastOrder(oblasts: List<Oblast>): List<Oblast> = oblasts.sortedBy {
        when (it.title) {
            "м. Київ" -> 0
            "Автономна Республіка Крим", "м. Севастополь" -> 2
            else -> 1
        }
    }
}

/** «Козинська громада · Обухівський р-н · Київська обл.» — від вужчого до ширшого. */
val SelectedRegion.label: String
    get() = (listOf(title) + path.split(" · ").filter { it.isNotBlank() }.reversed())
        .joinToString(" · ") { RegionNames.short(it) }

/** Лише назва самого регіону, коротко: «Козинська громада». */
val SelectedRegion.shortTitle: String
    get() = RegionNames.short(title)
