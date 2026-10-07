package dev.gridiron.feature.players

import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.GridPage
import dev.gridiron.core.data.StatFormat
import dev.gridiron.core.data.describeView

/** The Grid's shared card: the view, then the top [top] players by the current sort with the first [cols] columns. */
internal data class GridCard(val title: String, val headers: List<String>, val rows: List<Pair<String, List<String>>>)

internal fun gridCard(page: GridPage, catalog: Catalog, top: Int = 10, cols: Int = 4): GridCard = GridCard(
    title = describeView(page.request, catalog),
    headers = page.columns.take(cols).map { it.header },
    rows = page.rows.take(top).mapIndexed { i, row ->
        "${i + 1}. ${row.name}" to row.cells.take(cols).map { if (it.text == StatFormat.MISSING) "–" else it.display }
    },
)
