package dev.gridiron.core.statquery

/**
 * Generated SQL plus its positional binds, independent of any SQLite driver.
 *
 * On Android this becomes a Room 3 `RoomRawQuery`, binding each [Bind] by index
 * in `onBindStatement`. Construction fails if placeholders and binds disagree,
 * so a malformed query can't exist, let alone reach the database.
 */
public data class SqlQuery(val sql: String, val binds: List<Bind>) {
    init {
        val placeholders = sql.count { it == '?' }
        require(placeholders == binds.size) {
            "SQL has $placeholders placeholders but ${binds.size} binds"
        }
    }
}

/** A value bound to one `?`. The only way anything outside this module reaches a query. */
public sealed interface Bind {
    @JvmInline
    public value class Text(public val value: String) : Bind

    @JvmInline
    public value class Integer(public val value: Long) : Bind

    @JvmInline
    public value class Real(public val value: Double) : Bind
}

public data class GridQuery(val query: SqlQuery, val layout: GridLayout)

/**
 * Result column positions for a [GridQuery]: five fixed leading columns, then
 * each requested stat in order, each followed by its percentile when requested.
 */
public class GridLayout internal constructor(
    public val columns: List<StatColumn>,
    public val percentiles: Boolean,
) {
    private val stride: Int = if (percentiles) 2 else 1

    public val width: Int get() = FIRST_STAT + columns.size * stride

    public fun valueIndex(column: StatColumn): Int = FIRST_STAT + position(column) * stride

    public fun percentileIndex(column: StatColumn): Int {
        check(percentiles) { "percentiles were not requested" }
        return valueIndex(column) + 1
    }

    private fun position(column: StatColumn): Int {
        val i = columns.indexOf(column)
        require(i >= 0) { "$column is not a column in this query" }
        return i
    }

    // Value equality, so a GridQuery can key a result cache. Not a data class:
    // that would expose a public copy() around the internal constructor.
    override fun equals(other: Any?): Boolean =
        other is GridLayout && other.columns == columns && other.percentiles == percentiles

    override fun hashCode(): Int = 31 * columns.hashCode() + percentiles.hashCode()

    override fun toString(): String = "GridLayout(columns=$columns, percentiles=$percentiles)"

    public companion object {
        public const val PLAYER_ID: Int = 0
        public const val FULL_NAME: Int = 1
        public const val POSITION: Int = 2
        public const val TEAM: Int = 3
        public const val GAMES: Int = 4
        public const val FIRST_STAT: Int = 5
    }
}
