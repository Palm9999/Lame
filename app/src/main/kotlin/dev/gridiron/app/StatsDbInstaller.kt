package dev.gridiron.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies the bundled stats database out of the APK, where SQLite can open it.
 *
 * Copying a prebuilt, pre-indexed file takes about a second; building the same
 * database from JSON on the device would take far longer. It is recopied only
 * when the app itself is updated, which is when a newer database arrives.
 */
internal class StatsDbInstaller(private val context: Context) {

    suspend fun install(): File = withContext(Dispatchers.IO) {
        val dest = File(context.noBackupFilesDir, DB_NAME)
        val installedFrom = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            .lastUpdateTime
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (dest.isFile && prefs.getLong(KEY_INSTALLED_FROM, -1) == installedFrom) return@withContext dest

        // Copy to a temporary file and rename, so an interrupted copy can never
        // leave a truncated database that later opens as corrupt.
        val tmp = File(dest.path + ".tmp")
        context.assets.open(DB_NAME).use { input -> tmp.outputStream().use { input.copyTo(it, BUFFER) } }
        check(tmp.renameTo(dest)) { "couldn't move the stats database into place" }
        prefs.edit(commit = true) { putLong(KEY_INSTALLED_FROM, installedFrom) }
        dest
    }

    private companion object {
        const val DB_NAME = "stats.db"
        const val PREFS = "stats_db"
        const val KEY_INSTALLED_FROM = "installed_from_update_time"
        const val BUFFER = 1 shl 16
    }
}
