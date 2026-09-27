package ua.vidbiy.app.data

import android.os.SystemClock
import android.util.Log
import ua.vidbiy.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Адреса проксі. Задається під час збірки (див. app/build.gradle.kts), у застосунку її не змінити. */
object ProxyConfig {
    val BASE_URL: String = BuildConfig.PROXY_URL.trimEnd('/')
}

/** Жовтий — дронова загроза, червоний — ракетна (див. docs/proxy-api.md). Червоний — першим: він «вищий». */
@Serializable
enum class AlertLevel { RED, YELLOW }

/** Один рівень тривоги в регіоні. */
data class ActiveLevel(
    val level: AlertLevel,
    /** Коли рівень оголосили, мс за годинником сервера. Для правила 24 год (FR-27). */
    val sinceMillis: Long,
    /** Текст від ukrainealarm, лише для показу. */
    val reason: String?,
)

/**
 * Знімок стану тривог у момент відповіді проксі.
 *
 * [alerts] = null означає «ми не знаємо»: мережі немає, проксі мовчить або сам
 * ще не має стану від ukrainealarm. Порожня мапа — навпаки, перевірено: тривог немає.
 * Плутати ці два стани не можна, хоч будильник в обох випадках і дзвонить (NFR-1).
 */
data class AlertsSnapshot(
    /** Регіон (ID з довідника) → активні рівні повітряної тривоги в ньому. */
    val alerts: Map<String, List<ActiveLevel>>?,
    /**
     * Скільки секунд тому проксі востаннє підтвердив стан (FR-30). Понад 3 хв без
     * підтверджень — стану довіряти не можна (FR-31).
     */
    val ageSeconds: Long?,
    /** Показник монотонного лічильника телефона в момент отримання відповіді. */
    val receivedAtElapsed: Long,
) {
    /** Чи є в знімку взагалі на що спиратися. */
    val isKnown: Boolean get() = alerts != null && ageSeconds != null

    /**
     * Вік даних «зараз»: серверний вік плюс те, що минуло на телефоні.
     *
     * Рахувати як «зараз мінус fetched_at» не можна — годинник телефона може бути зсунутий
     * на хвилини. Тому час очікування міряємо elapsedRealtime: лічильником від старту
     * пристрою, який не залежить ні від годинника, ні від часових поясів.
     */
    fun effectiveAgeSeconds(nowElapsed: Long): Long? =
        ageSeconds?.plus((nowElapsed - receivedAtElapsed) / 1000)

    /**
     * FR-15: одна невдала спроба нічого не змінює. Якщо нова відповідь нічого не знає,
     * лишаємося на попередній відомій — вона старіє сама, і коли їй стане понад 3 хв,
     * будильник задзвонить.
     */
    fun orPrevious(previous: AlertsSnapshot?): AlertsSnapshot =
        if (isKnown || previous == null || !previous.isKnown) this else previous

    companion object {
        fun unavailable(nowElapsed: Long = SystemClock.elapsedRealtime()) =
            AlertsSnapshot(alerts = null, ageSeconds = null, receivedAtElapsed = nowElapsed)
    }
}

/** Клієнт проксі. Один запит — один знімок; повтори вирішує той, хто питає. */
class AlertsClient(private val baseUrl: String = ProxyConfig.BASE_URL) {

    suspend fun fetch(): AlertsSnapshot = withContext(Dispatchers.IO) {
        runCatching { request() }
            .onFailure { Log.w(TAG, "Проксі недоступний", it) }
            .getOrElse { AlertsSnapshot.unavailable() }
    }

    private fun request(): AlertsSnapshot {
        val connection = (URL("$baseUrl/v1/alerts").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // Довге очікування тут шкідливе: поки ми чекаємо, будильник мовчить.
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            val received = SystemClock.elapsedRealtime()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Проксі відповів ${connection.responseCode}")
                return AlertsSnapshot.unavailable(received)
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseAlertsResponse(body, received)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "AlertsClient"
        /** FR-8: окрема спроба не довша за ~8 с, щоб за 30 с встигнути кілька. */
        const val TIMEOUT_MS = 4_000

        /**
         * Хто ми такі. Без цього HttpURLConnection представляється системним рядком
         * Dalvik, а бот-захист Cloudflare перед *.workers.dev на незнайомі рядки вміє
         * відповідати 403 — запит тоді навіть не доходить до нашого проксі. Спіймали
         * це на скрипті з типовим `Python-urllib`: 403 у клієнта, порожньо в логах воркера.
         */
        val USER_AGENT = "Vidbiy/${BuildConfig.VERSION_NAME} (Android)"
    }
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Розбирає відповідь `/v1/alerts`. Незнайомий рівень вважаємо червоним: тривога з невідомим
 * рівнем — усе одно тривога. Кривий час початку — «щойно», тобто тривога рахується.
 */
internal fun parseAlertsResponse(body: String, receivedAtElapsed: Long): AlertsSnapshot {
    val response = json.decodeFromString<AlertsResponse>(body)
    return AlertsSnapshot(
        alerts = response.alerts?.associate { alert ->
            alert.region to alert.levels.map { level ->
                ActiveLevel(
                    level = if (level.level == "yellow") AlertLevel.YELLOW else AlertLevel.RED,
                    sinceMillis = runCatching { Instant.parse(level.since).toEpochMilli() }
                        .getOrDefault(System.currentTimeMillis()),
                    reason = level.reason,
                )
            }.ifEmpty { listOf(ActiveLevel(AlertLevel.RED, System.currentTimeMillis(), null)) }
        },
        ageSeconds = response.ageSeconds,
        receivedAtElapsed = receivedAtElapsed,
    )
}

/** Формат відповіді проксі — див. docs/proxy-api.md. */
@Serializable
private data class AlertsResponse(
    val v: Int = 1,
    /** Регіони з активною повітряною тривогою; null — проксі сам ще не знає стану. */
    val alerts: List<RegionAlertDto>? = null,
    @SerialName("age_seconds") val ageSeconds: Long? = null,
)

@Serializable
private data class RegionAlertDto(val region: String, val levels: List<LevelDto> = emptyList())

@Serializable
private data class LevelDto(val level: String = "red", val since: String = "", val reason: String? = null)
