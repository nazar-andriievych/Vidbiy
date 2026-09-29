package ua.vidbiy.app.data

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import ua.vidbiy.app.alarm.AlarmScheduler

/**
 * Резервна копія Android (Auto Backup). Самі файли копіює й відновлює система — цей клас
 * потрібен лише заради [onRestoreFinished].
 *
 * Android відновлює дані, але не розклад будильників в AlarmManager: його ставить
 * застосунок, коли запускається. Без цього класу на новому телефоні будильники мовчали б,
 * доки людина вперше не відкриє «Відбій».
 *
 * Під час відновлення процес працює в обмеженому режимі: VidbiyApplication не створюється,
 * тож репозиторій і планувальник беремо напряму.
 */
class VidbiyBackupAgent : BackupAgent() {

    // Ключ-значення (старий API) не використовуємо: fullBackupOnly у маніфесті.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        runCatching {
            val alarms = runBlocking { AlarmsRepository(this@VidbiyBackupAgent).disableMissed() }
            AlarmScheduler(this).scheduleAll(alarms)
            Log.i(TAG, "Відновлено з копії, будильників у розкладі: ${alarms.count { it.enabled }}")
        }.onFailure { Log.w(TAG, "Не вдалося поставити будильники після відновлення", it) }
    }

    private companion object {
        const val TAG = "VidbiyBackup"
    }
}
