package dev.gridiron.core.ingest.csv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.GZIPOutputStream

class CsvTest {
    private fun rows(csv: String, columns: List<String>, required: List<String> = columns): List<Map<String, String?>> {
        val out = mutableListOf<Map<String, String?>>()
        readCsv(csv.byteInputStream(), "test.csv", columns, required) { row -> out += columns.associateWith { row.text(it) } }
        return out
    }

    @Test
    fun `keeps only the requested columns and reads empty fields as null`() {
        val out = rows("a,b,c\n1,,3\n4,5,6\n", listOf("a", "b"))
        assertEquals(listOf(mapOf("a" to "1", "b" to null), mapOf("a" to "4", "b" to "5")), out)
    }

    @Test
    fun `quoted fields keep commas, doubled quotes and line breaks`() {
        val out = rows("id,desc,n\n1,\"Pass, short \"\"left\"\"\nto X\",7\n", listOf("desc", "n"))
        assertEquals("Pass, short \"left\"\nto X", out.single()["desc"])
        assertEquals("7", out.single()["n"])
    }

    @Test
    fun `windows line endings and a missing final newline`() {
        assertEquals(
            listOf(mapOf("a" to "1", "b" to "2"), mapOf("a" to "3", "b" to "4")),
            rows("a,b\r\n1,2\r\n3,4", listOf("a", "b")),
        )
    }

    @Test
    fun `a short row reads its missing trailing fields as null`() {
        assertEquals(listOf(mapOf("a" to "1", "b" to null)), rows("a,b\n1\n", listOf("a", "b")))
    }

    @Test
    fun `a missing required column is named in the error`() {
        val e = assertThrows<MissingColumnsException> { rows("a,c\n1,2\n", listOf("a", "b")) }
        assertEquals(listOf("b"), e.missing)
        assertTrue("nflverse changed column" in e.message!!)
    }

    @Test
    fun `an absent optional column reads as null`() {
        var present = true
        val out = mutableListOf<String?>()
        readCsv("a\n1\n".byteInputStream(), "t", listOf("a", "b"), required = listOf("a")) { row ->
            present = row.hasColumn("b")
            out += row.text("b")
        }
        assertFalse(present)
        assertEquals(listOf<String?>(null), out)
    }

    @Test
    fun `numeric accessors`() {
        readCsv("i,d,e\n3,-2.5,\n".byteInputStream(), "t", listOf("i", "d", "e")) { row ->
            assertEquals(3, row.int("i"))
            assertEquals(-2.5, row.double("d"))
            assertNull(row.double("e"))
        }
    }

    @Test
    fun `a byte order mark does not hide the first column`() {
        assertEquals(listOf(mapOf("a" to "1")), rows("\uFEFFa,b\n1,2\n", listOf("a")))
    }

    @Test
    fun `gzip files are decompressed`(@TempDir dir: File) {
        val file = File(dir, "x.csv.gz")
        GZIPOutputStream(file.outputStream()).use { it.write("a\n1\n".toByteArray()) }
        val out = mutableListOf<String?>()
        openInput(file).use { input -> readCsv(input, file.name, listOf("a")) { out += it.text("a") } }
        assertEquals(listOf<String?>("1"), out)
    }
}
