package ua.vidbiy.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Один запис журналу рішень: що бачив застосунок і що вирішив.
 *
 * Час — ISO-8601 з поясом телефона ([DecisionLog.now]), щоб звіряти з сервером (UTC) до секунди.
 * [levels] — рівні над покривними регіонами у вигляді `75:yellow@2026-09-29T07:30:12+03:00`.
 */
@Serializable
data class DecisionEntry(
    val at: String,
    /** fire, poll, ring, one_shot_check, snooze_from_wait, cancel_wait. */
    val event: String,
    val alarmId: Long? = null,
    val region: String? = null,
    val covering: List<String> = emptyList(),
    val waitFor: String? = null,
    val pauseMinutes: Int? = null,
    /** Серверний вік + час на телефоні, с. */
    val ageSeconds: Long? = null,
    /** `confirmed_at` з відповіді, на якій ґрунтується рішення (може бути з попередньої спроби). */
    val confirmedAt: String? = null,
    val levels: List<String> = emptyList(),
    val decision: String? = null,
    val step: String? = null,
    /** Результат цієї спроби запиту: `ok`, `http_503`, `timeout`, `no_network`, `bad_body`, `error:…`. */
    val fetch: String? = null,
    /** Скільки тривав цей запит, мс. */
    val fetchMillis: Long? = null,
    /** Скільки минуло від попереднього опитування (elapsedRealtime), с: чи не розтягує їх енергозбереження. */
    val gapSeconds: Long? = null,
    val note: String? = null,
)

/**
 * Журнал рішень будильника — **лише на телефоні**, нікуди не надсилається.
 *
 * Навіщо: logcat на Samsung живе хвилини, а питання «чому він задзвонив о 6:12 посеред
 * тривоги?» виникає вранці. Тут лишаються останні [MAX_LINES] записів у файлі JSON Lines
 * (по об'єкту в рядку) — це приблизно доба безперервного очікування. Забрати з ПК:
 * `adb exec-out run-as ua.vidbiy.app cat files/decisions.jsonl`.
 *
 * [log] не чекає на диск: запис іде в чергу, яку по одному пише окрема корутина. Так журнал
 * ніколи не затримує дзвінок, а записи лягають у файл у тому порядку, в якому їх подали.
 *
 * У резервну копію не потрапляє: backup_rules перелічують лише потрібні файли.
 */
class DecisionLog(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val queue = Channel<DecisionEntry>(Channel.UNLIMITED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (entry in queue) mutex.withLock { write(entry) }
        }
    }

    /** Подати запис. Повертається одразу; помилка запису лише потрапить у logcat. */
    fun log(entry: DecisionEntry) {
        queue.trySend(entry)
    }

    private fun write(entry: DecisionEntry) {
        try {
            file.appendText(json.encodeToString(DecisionEntry.serializer(), entry) + "\n")
            // Обрізаємо не щоразу, а коли файл помітно переріс ліміт.
            if (file.length() > TRIM_AT_BYTES) {
                // Спершу повна копія поруч, потім заміна: якщо процес уб'ють посеред запису,
                // старий журнал лишиться цілим.
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(file.readLines().takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не вдалося записати журнал рішень", e)
        }
    }

    /** Найновіші першими. */
    suspend fun read(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { file.readLines() }.getOrDefault(emptyList()).asReversed() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { file.delete() }
        Unit
    }

    companion object {
        private const val TAG = "VidbiyDecisions"
        const val FILE_NAME = "decisions.jsonl"
        /** ~доба опитувань кожні 30 с (2880) із запасом на інші події. */
        private const val MAX_LINES = 3000
        /** Запис опитування ~400 байтів: обрізаємо, коли набереться ~5000 рядків. */
        private const val TRIM_AT_BYTES = 2_000_000L
        private val json = Json { explicitNulls = false }
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        private val LEVEL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        /** Місцевий час телефона з поясом: і людині зрозуміло («дзвонив о 6:12»), і однозначно. */
        fun now(nowMillis: Long = System.currentTimeMillis()): String =
            Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).format(TIME)

        /** Рівні над покривними регіонами: `75:yellow@2026-09-29T07:30:12+03:00`. */
        fun describeLevels(region: SelectedRegion?, alerts: Map<String, List<ActiveLevel>>?): List<String> {
            if (region == null || alerts == null) return emptyList()
            return region.alertUids.sorted().flatMap { uid ->
                alerts[uid].orEmpty().map { level ->
                    val since = Instant.ofEpochMilli(level.sinceMillis).atZone(ZoneId.systemDefault()).format(LEVEL_TIME)
                    "$uid:${level.level.name.lowercase()}@$since"
                }
            }
        }
    }
}
