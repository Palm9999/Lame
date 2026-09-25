package dev.gridiron.feature.players

import dev.gridiron.core.data.DecimalInput
import dev.gridiron.core.data.FilterUnits
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn

internal enum class FilterOp(val symbol: String) { AT_LEAST("≥"), AT_MOST("≤"), BETWEEN("between") }

/** One row of the filter sheet as typed: values are text in display units. */
internal data class FilterRowDraft(
    val id: Int,
    val column: StatColumn,
    val op: FilterOp,
    val first: String = "",
    val second: String = "",
) {
    /** The filter this row means, or null while it's incomplete or invalid. */
    fun toFilter(): Filter? {
        val a = value(first) ?: return null
        return when (op) {
            FilterOp.AT_LEAST -> Filter(column, Condition.AtLeast(a))
            FilterOp.AT_MOST -> Filter(column, Condition.AtMost(a))
            FilterOp.BETWEEN -> {
                val b = value(second) ?: return null
                if (a > b) null else Filter(column, Condition.Between(a, b))
            }
        }
    }

    /** A fresh, empty row isn't an error yet; anything typed that doesn't parse is. */
    val showsError: Boolean
        get() = toFilter() == null && (first.isNotBlank() || second.isNotBlank())

    private fun value(text: String): Double? =
        (DecimalInput.parse(text, FilterUnits.FILTER_LIMIT) as? DecimalInput.Result.Value)
            ?.value?.let { FilterUnits.toStored(column, it) }

    companion object {
        fun from(id: Int, filter: Filter): FilterRowDraft {
            fun t(x: Double) = DecimalInput.format(FilterUnits.toInput(filter.column, x))
            return when (val c = filter.condition) {
                is Condition.AtLeast -> FilterRowDraft(id, filter.column, FilterOp.AT_LEAST, t(c.value))
                is Condition.AtMost -> FilterRowDraft(id, filter.column, FilterOp.AT_MOST, t(c.value))
                is Condition.Between -> FilterRowDraft(id, filter.column, FilterOp.BETWEEN, t(c.min), t(c.max))
                // The sheet never creates these; show them as their inclusive neighbors.
                is Condition.GreaterThan -> FilterRowDraft(id, filter.column, FilterOp.AT_LEAST, t(c.value))
                is Condition.LessThan -> FilterRowDraft(id, filter.column, FilterOp.AT_MOST, t(c.value))
            }
        }
    }
}

/** The filter sheet's working copy. Nothing reaches the Grid until Apply. */
internal data class FilterDraft(val rows: List<FilterRowDraft>, private val nextId: Int) {
    val complete: List<Filter> get() = rows.mapNotNull { it.toFilter() }
    val canAdd: Boolean get() = rows.size < GridRequest.MAX_FILTERS

    fun add(column: StatColumn): FilterDraft =
        if (!canAdd) this else FilterDraft(rows + FilterRowDraft(nextId, column, FilterOp.AT_LEAST), nextId + 1)

    fun update(row: FilterRowDraft): FilterDraft = copy(rows = rows.map { if (it.id == row.id) row else it })
    fun remove(id: Int): FilterDraft = copy(rows = rows.filterNot { it.id == id })
    fun clear(): FilterDraft = copy(rows = emptyList())

    companion object {
        fun of(filters: List<Filter>): FilterDraft =
            FilterDraft(filters.mapIndexed { i, f -> FilterRowDraft.from(i, f) }, filters.size)
    }
}
