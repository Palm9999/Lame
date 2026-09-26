package dev.gridiron.core.ingest

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/** Every row of [sql] against [file], each column as text (null stays null). */
internal fun query(file: File, sql: String): List<List<String?>> =
    BundledSQLiteDriver().open(file.path).use { conn ->
        conn.prepare(sql).use { st ->
            buildList {
                while (st.step()) add((0 until st.getColumnCount()).map { if (st.isNull(it)) null else st.getText(it) })
            }
        }
    }
