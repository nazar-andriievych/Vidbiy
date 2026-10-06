package ua.vidbiy.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ua.vidbiy.app.BuildConfig

/**
 * Лише для перевірки на телефоні (docs/testing.md, LOGIC-1): імітувати збій у циклі очікування,
 * щоб побачити, що будильник тоді дзвонить з «Збій у застосунку», а не мовчить.
 * У release-збірці нічого не робить, а приймача [DebugFailureReceiver] там немає в маніфесті.
 */
object DebugFailures {
    @Volatile
    private var failNextPoll = false

    fun requestPollFailure() {
        if (BuildConfig.DEBUG) failNextPoll = true
    }

    fun throwIfRequested() {
        if (!BuildConfig.DEBUG || !failNextPoll) return
        failNextPoll = false
        throw IllegalStateException("Debug: імітований збій циклу очікування")
    }
}

/**
 * `adb shell am broadcast -n ua.vidbiy.app/.alarm.DebugFailureReceiver` — наступне опитування
 * служби очікування впаде. Зареєстрований лише в src/debug/AndroidManifest.xml і лише для
 * відправника з дозволом DUMP (його має adb shell, але не застосунки).
 */
class DebugFailureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w("VidbiyWait", "Debug: наступне опитування впаде")
        DebugFailures.requestPollFailure()
    }
}
