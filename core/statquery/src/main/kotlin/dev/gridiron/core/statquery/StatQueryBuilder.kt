package dev.gridiron.core.statquery

/**
 * Turns a [StatQuerySpec] into SQL over the ETL's long/narrow fact table.
 *
 * Query shape:
 *
 *  1. `agg` pivots `player_week_stat` to one row per player, summing only the
 *     components the requested columns need. Its predicate is
 *     `metric_id IN (...) AND season = ? AND week BETWEEN ? AND ?`, which is
 *     exactly the `idx_pws_metric_season_week` index.
 *  2. `base` computes each column from those sums, so rates are recomputed over
 *     the range rather than averaged, and applies the games floor.
 *  3. `ranked`, only when percentiles are requested, adds positional
 *     percentiles. It runs before the user's filters so that narrowing the
 *     view never changes anyone's percentile.
 *  4. The outer query filters, sorts with NULLs last, and pages.
 *
 * Safety: identifiers in the SQL are only fixed text and index-derived aliases
 * (`k0`, `v3`, `p3`). Every value, including metric ids, is a bound `?`.
 *
 * Percentiles use `PERCENT_RANK`, which needs SQLite 3.25+. That's satisfied by
 * both `androidx.sqlite:sqlite-bundled` and the platform SQLite on the target
 * device.
 */
public object StatQueryBuilder {

    public const val DEFAULT_SEARCH_LIMIT: Int = 20

    public fun grid(spec: StatQuerySpec): GridQuery {
        // Displayed columns first, so their alias index equals display order.
        val plan = Plan(spec.columns + spec.sort.map { it.column } + spec.filters.map { it.column })
        val w = SqlWriter()

        w.aggregateAndBase(spec, plan)
        val source = if (spec.percentiles) {
            w.ranked(spec, plan)
            "ranked"
        } else {
            "base"
        }

        w.line("SELECT player_id, full_name, position, team, games")
        for (column in spec.columns) {
            val i = plan.index(column)
            w.line(if (spec.percentiles) "     , v$i, p$i" else "     , v$i")
        }
        w.line("FROM $source")
        w.where(spec, plan)
        w.orderBy(spec, plan)
        w.line("LIMIT ${w.int(spec.limit)} OFFSET ${w.int(spec.offset)}")

        return GridQuery(w.build(), GridLayout(spec.columns, spec.percentiles))
    }

    /**
     * Rows matching the spec's filters, ignoring sort and paging. Backs the
     * live match count on the advanced filter sheet. Only the components the
     * filters need are aggregated, so it is cheaper than [grid].
     */
    public fun count(spec: StatQuerySpec): SqlQuery {
        val plan = Plan(spec.filters.map { it.column })
        val w = SqlWriter()
        w.aggregateAndBase(spec, plan)
        w.line("SELECT COUNT(*)")
        w.line("FROM base")
        w.where(spec, plan)
        return w.build()
    }

    /**
     * Player lookup by name. Full-name prefix matches rank first, then word
     * prefixes, so "chase" finds Ja'Marr Chase. Returns null when the text has
     * nothing searchable in it.
     *
     * Result columns: player_id, full_name, position, team.
     */
    public fun search(text: String, limit: Int = DEFAULT_SEARCH_LIMIT): SqlQuery? {
        require(limit in 1..StatQuerySpec.MAX_LIMIT) { "limit $limit outside 1..${StatQuerySpec.MAX_LIMIT}" }
        val q = normalizeSearch(text)
        if (q.isEmpty()) return null
        val w = SqlWriter()
        w.line("SELECT player_id, full_name, position, team")
        w.line("FROM player")
        w.line("WHERE ${w.nameMatch(q)}")
        w.line("ORDER BY ${w.prefixMatch(q)} DESC, full_name ASC, player_id ASC")
        w.line("LIMIT ${w.int(limit)}")
        return w.build()
    }
}

/** The columns a query computes and the components they need, with stable aliases. */
private class Plan(columns: List<StatColumn>) {
    val columns: List<StatColumn> = columns.distinct()

    // Sorted so identical specs produce identical SQL.
    val components: List<Component> =
        (this.columns.flatMap { it.aggregate.components } + Components.GAMES)
            .distinct()
            .sortedBy { it.id }

    val games: String = ref(Components.GAMES)

    fun index(column: StatColumn): Int {
        val i = columns.indexOf(column)
        check(i >= 0) { "$column is not planned" }
        return i
    }

    fun ref(component: Component): String {
        val i = components.indexOf(component)
        check(i >= 0) { "$component is not planned" }
        return "agg.k$i"
    }

    fun valueExpr(column: StatColumn, mode: ValueMode): String {
        val expr = column.aggregate.toSql(::ref)
        return if (mode == ValueMode.PER_GAME && column.aggregate.scalesWithGames) {
            "(1.0 * $expr / NULLIF($games, 0))"
        } else {
            expr
        }
    }
}

/**
 * Accumulates SQL and binds together. Each bind helper appends its value and
 * returns `?` at the moment it's written into the text, so bind order always
 * follows placeholder order.
 */
private class SqlWriter {
    private val sql = StringBuilder()
    private val binds = mutableListOf<Bind>()

    fun line(text: String) {
        sql.append(text).append('\n')
    }

    fun text(value: String): String {
        binds += Bind.Text(value)
        return "?"
    }

    fun int(value: Int): String {
        binds += Bind.Integer(value.toLong())
        return "?"
    }

    fun real(value: Double): String {
        binds += Bind.Real(value)
        return "?"
    }

    fun build(): SqlQuery = SqlQuery(sql.toString().trimEnd(), binds.toList())

    fun aggregateAndBase(spec: StatQuerySpec, plan: Plan) {
        line("WITH agg AS (")
        line("  SELECT s.player_id")
        plan.components.forEachIndexed { i, c ->
            line("       , SUM(CASE WHEN s.metric_id = ${text(c.id)} THEN s.value END) AS k$i")
        }
        line("  FROM player_week_stat s")
        line("  WHERE s.metric_id IN (${plan.components.joinToString(", ") { text(it.id) }})")
        line("    AND s.season = ${int(spec.season)}")
        line("    AND s.week BETWEEN ${int(spec.weeks.first)} AND ${int(spec.weeks.last)}")
        line("  GROUP BY s.player_id")
        line("), base AS (")
        line("  SELECT p.player_id, p.full_name, p.position, p.team, p.search_name, ${plan.games} AS games")
        plan.columns.forEachIndexed { i, column ->
            line("       , ${plan.valueExpr(column, spec.mode)} AS v$i")
        }
        line("  FROM agg")
        line("  JOIN player p ON p.player_id = agg.player_id")
        line("  WHERE ${plan.games} >= ${int(spec.minGames)}")
        line(")")
    }

    fun ranked(spec: StatQuerySpec, plan: Plan) {
        line(", ranked AS (")
        line("  SELECT base.*")
        for (column in spec.columns) {
            val i = plan.index(column)
            // Best = 1.0. Partitioning on nullness keeps players without a value
            // from diluting everyone else's rank.
            val order = if (column.higherIsBetter) "ASC" else "DESC"
            line(
                "       , CASE WHEN v$i IS NULL THEN NULL ELSE PERCENT_RANK() OVER " +
                    "(PARTITION BY position, v$i IS NULL ORDER BY v$i $order) END AS p$i",
            )
        }
        line("  FROM base")
        line(")")
    }

    fun where(spec: StatQuerySpec, plan: Plan) {
        val conditions = mutableListOf<String>()
        if (spec.positions.isNotEmpty()) {
            val codes = spec.positions.sortedBy { it.ordinal }
            conditions += "position IN (${codes.joinToString(", ") { text(it.code) }})"
        }
        if (spec.teams.isNotEmpty()) {
            conditions += "team IN (${spec.teams.sorted().joinToString(", ") { text(it) }})"
        }
        spec.name?.let(::normalizeSearch)?.takeIf { it.isNotEmpty() }?.let { q ->
            conditions += nameMatch(q)
        }
        for (filter in spec.filters) {
            val v = "v${plan.index(filter.column)}"
            conditions += when (val c = filter.condition) {
                is Condition.AtLeast -> "$v >= ${real(c.value)}"
                is Condition.AtMost -> "$v <= ${real(c.value)}"
                is Condition.GreaterThan -> "$v > ${real(c.value)}"
                is Condition.LessThan -> "$v < ${real(c.value)}"
                is Condition.Between -> "$v BETWEEN ${real(c.min)} AND ${real(c.max)}"
            }
        }
        if (conditions.isNotEmpty()) {
            line("WHERE ${conditions.joinToString("\n  AND ")}")
        }
    }

    fun orderBy(spec: StatQuerySpec, plan: Plan) {
        val sort = spec.sort.ifEmpty { listOf(Sort(spec.columns.first())) }
        val keys = sort.map { s ->
            val v = "v${plan.index(s.column)}"
            val dir = if (s.direction == Direction.ASCENDING) "ASC" else "DESC"
            // NULLs last in both directions, portably (NULLS LAST needs 3.30+).
            "$v IS NULL, $v $dir"
        }
        // Name, then id, as tiebreakers so paging is stable across requests.
        line("ORDER BY ${(keys + "full_name ASC" + "player_id ASC").joinToString(", ")}")
    }

    /**
     * Full-name prefix via the range trick, which uses `idx_player_search`
     * where `LIKE 'x%'` would not, or any later word's prefix. Normalized text
     * holds only `[a-z0-9 ]`, so it can't carry LIKE wildcards. The upper bound
     * `￿` sorts above every ASCII byte under SQLite's BINARY collation.
     */
    fun nameMatch(q: String): String =
        "(${prefixMatch(q)} OR search_name LIKE ${text("% $q%")})"

    fun prefixMatch(q: String): String =
        "(search_name >= ${text(q)} AND search_name < ${text(q + '￿')})"
}
