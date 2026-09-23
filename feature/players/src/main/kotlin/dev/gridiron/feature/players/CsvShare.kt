package dev.gridiron.feature.players

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Writes a CSV under cache/exports and hands it to the share sheet. The file
 * is written beside its final name and renamed into place, so a failed write
 * never leaves a partial file to share. The app declares the provider, with
 * authority `<applicationId>.exports`.
 */
public object CsvShare {
    public suspend fun share(context: Context, fileName: String, csv: String): Boolean {
        val file = try {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val tmp = File(dir, "$fileName.tmp")
                tmp.writeText(csv)
                val out = File(dir, fileName)
                if (!tmp.renameTo(out)) {
                    out.delete()
                    if (!tmp.renameTo(out)) throw IOException("couldn't move $tmp to $out")
                }
                out
            }
        } catch (e: IOException) {
            return false
        }
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export CSV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
