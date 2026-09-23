package dev.gridiron.app

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import dev.gridiron.feature.players.CsvShare
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CsvShareTest {
    // FileProvider caches each authority's resolved roots in a static map, keyed
    // only by authority string, not by context. Robolectric gives each test its
    // own sandboxed cacheDir but reuses the same classloader (and thus that
    // static map) across tests in this class, so a stale root from an earlier
    // test's context would otherwise make this test's real path look
    // unconfigured. Clearing it before each test keeps them independent.
    @Before
    fun resetFileProviderCache() {
        val field = FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible = true
        (field.get(null) as MutableMap<*, *>).clear()
    }

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

    @Test
    fun overwritesAnExistingExportAndLeavesNoTmpFileBehind() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        val dest = File(dir, "gridiron-2025-receiving.csv")
        dest.writeText("old,content\r\n")

        val ok = runBlocking { CsvShare.share(app, "gridiron-2025-receiving.csv", "new,content\r\n") }

        assertTrue(ok)
        assertEquals("new,content\r\n", dest.readText())
        assertTrue(dir.listFiles { f -> f.name.endsWith(".tmp") }.orEmpty().isEmpty())
    }
}
