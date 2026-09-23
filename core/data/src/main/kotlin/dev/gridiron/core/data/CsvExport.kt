package dev.gridiron.core.data

/**
 * The Grid as a CSV (RFC 4180, CRLF), values exactly as displayed. A first
 * `#` line describes the view, since a spreadsheet shows it as its first row,
 * and the last line carries the nflverse attribution its license asks for.
 */
public object CsvExport {
    public fun build(page: GridPage, catalog: Catalog): String {
        val out = StringBuilder()
        fun line(fields: List<String>) {
            fields.joinTo(out, ",") { field(it) }
            out.append("\r\n")
        }
        line(listOf("# " + describeView(page.request, catalog)))
        line(listOf("Rank", "Player", "Pos", "Team", "Games") + page.columns.map { it.header })
        page.rows.forEachIndexed { i, row ->
            line(
                listOf((i + 1).toString(), row.name, row.position.orEmpty(), row.team.orEmpty(), row.games.toString()) +
                    row.cells.map { if (it.text == StatFormat.MISSING) "" else it.text },
            )
        }
        line(listOf("# Data: nflverse (CC BY 4.0)"))
        return out.toString()
    }

    public fun fileName(request: GridRequest): String =
        "gridiron-${request.season.season}-${request.pack.name.lowercase()}.csv"

    private fun field(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
