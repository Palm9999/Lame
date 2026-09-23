package dev.gridiron.core.data

import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import java.math.BigDecimal

/**
 * Filters are typed as values are displayed: a percent column as a
 * percentage, everything else as is. BigDecimal keeps 0.65 ↔ 65 exact, so a
 * saved filter never reads back as "65.00000000000001".
 */
public object FilterUnits {
    /** Large enough for any season total (passing yards), small enough to reject junk. */
    public const val FILTER_LIMIT: Double = 100_000.0

    public fun toInput(column: StatColumn, stored: Double): Double =
        if (StatFormat.isPercent(column)) BigDecimal.valueOf(stored).movePointRight(2).toDouble() else stored

    public fun toStored(column: StatColumn, input: Double): Double =
        if (StatFormat.isPercent(column)) BigDecimal.valueOf(input).movePointLeft(2).toDouble() else input
}

/** "TGT ≥ 50", "CTCH% ≤ 60", "TGT 20–40". */
public fun describeFilter(filter: Filter, catalog: Catalog): String {
    val abbr = catalog.metrics[filter.column.metricId]?.abbr ?: filter.column.metricId
    fun v(x: Double) = DecimalInput.format(FilterUnits.toInput(filter.column, x))
    return when (val c = filter.condition) {
        is Condition.AtLeast -> "$abbr ≥ ${v(c.value)}"
        is Condition.AtMost -> "$abbr ≤ ${v(c.value)}"
        is Condition.Between -> "$abbr ${v(c.min)}–${v(c.max)}"
        is Condition.GreaterThan -> "$abbr > ${v(c.value)}"
        is Condition.LessThan -> "$abbr < ${v(c.value)}"
    }
}

/** One line naming everything that shapes the table, for the CSV header. */
public fun describeView(r: GridRequest, catalog: Catalog): String = buildList {
    add("Gridiron")
    add(r.season.season.toString())
    add(weeksLabel(r.season, r.weeks))
    add(r.pack.label)
    add(r.positions.label)
    add(r.scoring.name)
    if (r.perGame) add("per game")
    if (r.teams.isNotEmpty()) add(r.teams.sorted().joinToString("/"))
    r.minSnapShare?.let { add(describeFilter(Filter(StatColumn.SNAP_SHARE, Condition.AtLeast(it)), catalog)) }
    r.filters.forEach { add(describeFilter(it, catalog)) }
}.joinToString(" · ")

/** The filter sheet's stat picker: this pack's columns, then the other packs', then the rest. */
public fun filterColumnOrder(pack: StatPack): List<StatColumn> =
    (pack.columns + StatPack.entries.flatMap { it.columns } + StatColumn.entries).distinct()
