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

/** Адреса проксі. Одна на весь застосунок; debug-збірка вміє її перекрити (див. налаштування). */
object ProxyConfig {
    const val BASE_URL = "https://vidbiy-proxy.nazar-dev.workers.dev"
}

/**
 * Знімок стану тривог у момент відповіді проксі.
 *
 * [alertUids] = null означає «ми не знаємо»: мережі немає, проксі мовчить або сам
 * ще не має стану від ukrainealarm. Порожній набір — навпаки, перевірено: тривог немає.
 * Плутати ці два стани не можна, хоч будильник в обох випадках і дзвонить (NFR-1).
 */
data class AlertsSnapshot(
    val alertUids: Set<String>?,
    /**
     * Скільки секунд тому проксі востаннє підтвердив стан: вебхуком від ukrainealarm
     * або власною перевіркою, коли вебхуків немає. Понад 3 хв без підтверджень —
     * стану довіряти не можна (NFR-1).
     */
    val ageSeconds: Long?,
    /** Показник монотонного лічильника телефона в момент отримання відповіді. */
    val receivedAtElapsed: Long,
) {
    /**
     * Вік даних «зараз»: серверний вік плюс те, що минуло на телефоні.
     *
     * Рахувати як «зараз мінус fetched_at» не можна — годинник телефона може бути зсунутий
     * на хвилини. Тому час очікування міряємо elapsedRealtime: лічильником від старту
     * пристрою, який не залежить ні від годинника, ні від часових поясів.
     */
    fun effectiveAgeSeconds(nowElapsed: Long): Long? =
        ageSeconds?.plus((nowElapsed - receivedAtElapsed) / 1000)

    companion object {
        fun unavailable(nowElapsed: Long = SystemClock.elapsedRealtime()) =
            AlertsSnapshot(alertUids = null, ageSeconds = null, receivedAtElapsed = nowElapsed)
    }
}

/** Клієнт проксі. Один запит — один знімок; жодних ретраїв: наступна спроба буде за 30 с. */
class AlertsClient(private val baseUrl: String = ProxyConfig.BASE_URL) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(): AlertsSnapshot = withContext(Dispatchers.IO) {
        runCatching { request() }
            .onFailure { Log.w(TAG, "Проксі недоступний", it) }
            .getOrElse { AlertsSnapshot.unavailable() }
    }

    private fun request(): AlertsSnapshot {
        val connection = (URL("$baseUrl/v2/alerts").openConnection() as HttpURLConnection).apply {
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
            val response = json.decodeFromString<AlertsResponse>(body)
            return AlertsSnapshot(
                alertUids = response.active?.toSet(),
                ageSeconds = response.ageSeconds,
                receivedAtElapsed = received,
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "AlertsClient"
        const val TIMEOUT_MS = 8_000

        /**
         * Хто ми такі. Без цього HttpURLConnection представляється системним рядком
         * Dalvik, а бот-захист Cloudflare перед *.workers.dev на незнайомі рядки вміє
         * відповідати 403 — запит тоді навіть не доходить до нашого проксі. Спіймали
         * це на скрипті з типовим `Python-urllib`: 403 у клієнта, порожньо в логах воркера.
         */
        val USER_AGENT = "Vidbiy/${BuildConfig.VERSION_NAME} (Android)"
    }
}

/** Формат відповіді проксі — див. docs/proxy-api.md. */
@Serializable
private data class AlertsResponse(
    val v: Int = 2,
    /** ID регіонів із активною повітряною тривогою; null — проксі сам ще не знає стану. */
    val active: List<String>? = null,
    @SerialName("age_seconds") val ageSeconds: Long? = null,
)
