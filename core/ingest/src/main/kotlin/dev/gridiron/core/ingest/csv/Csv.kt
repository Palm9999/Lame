package dev.gridiron.core.ingest.csv

import java.io.File
import java.io.InputStream
import java.io.Reader
import java.util.zip.GZIPInputStream

/** A column the build needs is missing from an upstream file: nflverse renamed or dropped it. */
public class MissingColumnsException(
    public val source: String,
    public val missing: List<String>,
) : IllegalStateException("nflverse changed column(s) ${missing.joinToString()} in $source")

/**
 * The current data row. Only requested columns are held. An empty field, or a
 * requested column the file doesn't have, reads as null. The same instance is
 * reused for every record, so read what you need inside the callback.
 */
internal class CsvRow(
    private val slots: Map<String, Int>,
    private val present: Set<String>,
) {
    val values: Array<String?> = arrayOfNulls(slots.size)

    fun hasColumn(name: String): Boolean = name in present

    fun text(name: String): String? = values[slots[name] ?: error("column $name was not requested")]

    fun double(name: String): Double? = text(name)?.toDoubleOrNull()

    fun int(name: String): Int? = double(name)?.toInt()
}

/**
 * Streams RFC 4180 CSV from [input], calling [onRow] once per data row. Quoted
 * fields may hold commas, doubled quotes and line breaks. Every name in
 * [required] must be in the header; other [columns] read as null when absent.
 * Only requested fields are copied out of the stream, so a 372-column
 * play-by-play file costs little more than its bytes.
 */
internal fun readCsv(
    input: InputStream,
    source: String,
    columns: Collection<String>,
    required: Collection<String> = columns,
    onRow: (CsvRow) -> Unit,
) {
    val tokens = Tokenizer(input.reader(Charsets.UTF_8))
    val header = tokens.header() ?: throw MissingColumnsException(source, required.toList())
    val missing = required.filter { it !in header }
    if (missing.isNotEmpty()) throw MissingColumnsException(source, missing)

    val wanted = columns.distinct()
    val slots = wanted.withIndex().associate { (i, name) -> name to i }
    val slotOf = IntArray(header.size) { slots[header[it]] ?: -1 }
    val row = CsvRow(slots, wanted.filterTo(HashSet()) { it in header })
    while (true) {
        row.values.fill(null)
        if (!tokens.record(slotOf, row.values)) break
        onRow(row)
    }
}

/** Opens [file], decompressing it when its name ends in `.gz`. */
internal fun openInput(file: File): InputStream {
    val raw = file.inputStream().buffered(BUFFER)
    return if (file.name.endsWith(".gz")) GZIPInputStream(raw, BUFFER) else raw
}

private const val BUFFER = 1 shl 16
private const val EOF = -1

private class Tokenizer(private val reader: Reader) {
    private val buf = CharArray(BUFFER)
    private var len = 0
    private var pos = 0
    private val field = StringBuilder()

    fun header(): List<String>? {
        val names = mutableListOf<String>()
        if (!next({ true }) { _, value -> names += value }) return null
        names[0] = names[0].removePrefix("\uFEFF")
        return names
    }

    fun record(slotOf: IntArray, out: Array<String?>): Boolean =
        next({ it < slotOf.size && slotOf[it] >= 0 }) { i, value -> out[slotOf[i]] = value.ifEmpty { null } }

    private fun read(): Int {
        if (pos == len) {
            len = reader.read(buf, 0, buf.size)
            pos = 0
            if (len <= 0) {
                len = 0
                return EOF
            }
        }
        return buf[pos++].code
    }

    /** Reads one record; false at end of input. Blank lines are skipped. */
    private inline fun next(keep: (Int) -> Boolean, emit: (Int, String) -> Unit): Boolean {
        var c = read()
        while (c == '\n'.code || c == '\r'.code) c = read()
        if (c == EOF) return false
        var index = 0
        while (true) {
            val kept = keep(index)
            field.setLength(0)
            if (c == '"'.code) {
                while (true) {
                    c = read()
                    if (c == EOF) break
                    if (c == '"'.code) {
                        c = read()
                        if (c != '"'.code) break
                    }
                    if (kept) field.append(c.toChar())
                }
            } else {
                while (c != EOF && c != ','.code && c != '\n'.code && c != '\r'.code) {
                    if (kept) field.append(c.toChar())
                    c = read()
                }
            }
            if (kept) emit(index, field.toString())
            index++
            when (c) {
                ','.code -> c = read()
                '\r'.code -> {
                    val after = read()
                    if (after != '\n'.code && after != EOF) pos--
                    return true
                }
                else -> return true
            }
        }
    }
}
