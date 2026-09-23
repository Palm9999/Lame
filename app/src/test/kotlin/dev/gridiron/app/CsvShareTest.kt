package dev.gridiron.app

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.gridiron.feature.players.CsvShare
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CsvShareTest {
    @Test
    fun writesTheFileAndOffersItToOtherApps() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val ok = runBlocking { CsvShare.share(app, "gridiron-2025-receiving.csv", "a,b\r\n1,2\r\n") }
        assertTrue(ok)
        assertEquals("a,b\r\n1,2\r\n", File(app.cacheDir, "exports/gridiron-2025-receiving.csv").readText())

        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/csv", send.type)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${app.packageName}.exports", uri.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
