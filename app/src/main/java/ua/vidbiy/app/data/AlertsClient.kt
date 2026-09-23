package ua.vidbiy.app.data

import android.os.SystemClock
import android.util.Log
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
 * не має даних від alerts.in.ua. Порожній набір — навпаки, перевірено: тривог немає.
 * Плутати ці два стани не можна, хоч будильник в обох випадках і дзвонить (NFR-1).
 */
data class AlertsSnapshot(
    val alertUids: Set<String>?,
    /** Вік даних на сервері в момент відповіді, секунди. */
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
        val connection = (URL("$baseUrl/v1/alerts").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // Довге очікування тут шкідливе: поки ми чекаємо, будильник мовчить.
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
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
                alertUids = response.alerts?.map { it.uid }?.toSet(),
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
    }
}

/** Формат відповіді проксі — див. docs/proxy-api.md. */
@Serializable
private data class AlertsResponse(
    val v: Int = 1,
    @SerialName("upstream_ok") val upstreamOk: Boolean = false,
    @SerialName("age_seconds") val ageSeconds: Long? = null,
    val alerts: List<ActiveAlert>? = null,
)

@Serializable
private data class ActiveAlert(
    val uid: String,
    val type: String = "unknown",
    @SerialName("started_at") val startedAt: String? = null,
)
