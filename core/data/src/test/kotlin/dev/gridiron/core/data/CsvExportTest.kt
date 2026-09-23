package dev.gridiron.core.data

import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CsvExportTest {
    private val season = SeasonInfo(2025, lastWeek = 22)
    private val catalog = Catalog(persistentListOf(season), persistentMapOf())
    private val request = GridRequest(season, WeekRange(1, 8), StatPack.RECEIVING)

    private fun row(name: String, pos: String?, team: String?, vararg cells: String) =
        GridRowUi("id-$name", name, "detail", pos, team, 8, cells.map { CellUi(it, null) }.let { persistentListOf(*it.toTypedArray()) })

    private val page = GridPage(
        request,
        persistentListOf(
            ColumnUi(StatColumn.TARGETS, "TGT", null),
            ColumnUi(StatColumn.CATCH_RATE, "CTCH%", null),
        ),
        persistentListOf(
            row("Amon-Ra St. Brown", "WR", "DET", "88", "71.6%"),
            row("Smith, Jr. \"Deuce\"", null, null, "12", StatFormat.MISSING),
        ),
        threshold = null,
    )

    @Test
    fun `writes a view line, a header, one row per player and the attribution, with CRLF`() {
        val lines = CsvExport.build(page, catalog).split("\r\n")
        assertEquals("# Gridiron · 2025 · Wk 1–8 · Receiving · All · PPR", lines[0])
        assertEquals("Rank,Player,Pos,Team,Games,TGT,CTCH%", lines[1])
        assertEquals("1,Amon-Ra St. Brown,WR,DET,8,88,71.6%", lines[2])
        assertEquals("2,\"Smith, Jr. \"\"Deuce\"\"\",,,8,12,", lines[3])
        assertEquals("# Data: nflverse (CC BY 4.0)", lines[4])
        assertEquals("", lines[5]) // trailing CRLF
        assertEquals(6, lines.size)
    }

    @Test
    fun `file name names the season and pack`() {
        assertEquals("gridiron-2025-receiving.csv", CsvExport.fileName(request))
    }
}
