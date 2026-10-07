package ua.vidbiy.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.vidbiy.app.data.AppUpdate

/** Що показує банер оновлення на головному екрані. */
class UpdateNoticeTest {

    private val release = AppUpdate(latestVersionCode = 3, latestVersionName = "1.2", minVersionCode = 2)

    @Test
    fun `проксі про випуск не казав — банера немає`() {
        assertEquals(UpdateNotice.NONE, updateNotice(null, versionCode = 1, dismissedCode = 0))
    }

    @Test
    fun `стоїть остання версія — банера немає`() {
        assertEquals(UpdateNotice.NONE, updateNotice(release, versionCode = 3, dismissedCode = 0))
    }

    @Test
    fun `є новіша версія — банер, який можна закрити`() {
        assertEquals(UpdateNotice.AVAILABLE, updateNotice(release, versionCode = 2, dismissedCode = 0))
    }

    @Test
    fun `закритий банер не повертається до наступного випуску`() {
        assertEquals(UpdateNotice.NONE, updateNotice(release, versionCode = 2, dismissedCode = 3))
        assertEquals(UpdateNotice.AVAILABLE, updateNotice(release.copy(latestVersionCode = 4), versionCode = 2, dismissedCode = 3))
    }

    @Test
    fun `застарілу версію закрити не можна`() {
        assertEquals(UpdateNotice.REQUIRED, updateNotice(release, versionCode = 1, dismissedCode = 3))
    }
}
