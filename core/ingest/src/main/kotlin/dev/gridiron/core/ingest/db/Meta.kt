package dev.gridiron.core.ingest.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.io.File

/** A stats database's `schema_meta`, or null if [file] is missing or isn't a stats database. */
public fun readMeta(file: File): Map<String, String>? {
    if (!file.isFile) return null
    return runCatching {
        BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READONLY).use { conn ->
            conn.prepare("SELECT key, value FROM schema_meta").use { st ->
                buildMap { while (st.step()) put(st.getText(0), st.getText(1)) }
            }
        }
    }.getOrNull()
}
