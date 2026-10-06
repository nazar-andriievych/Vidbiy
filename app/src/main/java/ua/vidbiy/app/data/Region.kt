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
 * Рішення бере не його, а [alertUids]: там правила, що змінились після збереження.
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
    coveringUids = SEPARATE_CITIES[uid]?.let { setOf(uid, it) } ?: setOf(uid, raion.uid, oblast.uid),
)

/**
 * Міста, які ukrainealarm веде окремо від їхнього району (FR-29): UID громади → UID області.
 *
 * У нашому довіднику це громади, а в ukrainealarm — регіони верхнього рівня зі своїм статусом.
 * Тривога району там означає «район без міста»: за 48 год (2026-10-04 … 10-06) Харківський район
 * був у тривозі без міста ~8 год, Запорізький — ~2 год. Тривога, що стосується міста, оголошується
 * на саме місто, тож район для нього не рахуємо. Область лишаємо: тривог на всю область за цей час
 * не було, а якщо вони є, то найімовірніше стосуються й міста.
 */
val SEPARATE_CITIES: Map<String, String> = mapOf(
    "1293" to "22", // м. Харків — Харківська область
    "564" to "12", // м. Запоріжжя — Запорізька область
)

/**
 * Громади з довідника alerts.in.ua, яких немає в ukrainealarm: UID → UID, під яким ukrainealarm
 * веде цю територію. У Сумському районі дві «Миколаївські» громади, а ukrainealarm знає лише 1180;
 * 1181 з довідника прибрано (tools/build-regions.mjs), а збережені раніше вибори рахуються як 1180.
 */
val MERGED_UIDS: Map<String, String> = mapOf("1181" to "1180")

/**
 * UID регіонів, тривога в яких накриває обраний, — за нинішніми правилами.
 * Будильники й місця, збережені раніше, мають у [SelectedRegion.coveringUids] ще й район міста
 * (FR-29) чи громаду, якої вже немає в довіднику; тут правила застосовуються без перезбереження.
 */
val SelectedRegion.alertUids: Set<String>
    get() = (SEPARATE_CITIES[uid]?.let { setOf(uid, it) } ?: coveringUids)
        .mapTo(mutableSetOf()) { MERGED_UIDS[it] ?: it }

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
