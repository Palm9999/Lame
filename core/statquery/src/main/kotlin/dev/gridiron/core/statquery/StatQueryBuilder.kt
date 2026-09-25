package dev.gridiron.core.statquery

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule

/**
 * [SCORING_COMPONENTS] split by whether a rule input is actual or expected
 * (disjoint: every id is `x_`-prefixed in one set and not in the other). The
 * `scoring()` CTEs pivot each separately, so a row only ever pays for the
 * branches of the set it belongs to, not all of [SCORING_COMPONENTS].
 */
private val ACTUAL_COMPONENTS: List<Component> =
    (RULE_INPUTS.values.flatMap { it.actual }.map { it.component } + BONUS_INPUTS.values.flatten())
        .distinct()
        .sortedBy { it.id }

private val EXPECTED_COMPONENTS: List<Component> =
    RULE_INPUTS.values.flatMap { it.expected }.map { it.component }.distinct().sortedBy { it.id }

/**
 * Turns a [StatQuerySpec] into SQL over the ETL's long/narrow fact table.
 *
 * Query shape:
 *
 *  0. When a fantasy column is planned, `wk` pivots `player_week_stat` to one
 *     row per player-week for every scoring component, `fw` applies the
 *     spec's scoring profile to each week (so per-game bonuses see single
 *     games), and `fsum` totals `fw` per player into fantasy points, expected
 *     fantasy points and FPOE.
 *  1. `agg` pivots `player_week_stat` to one row per player, summing only the
 *     components the requested columns need. Its predicate is
 *     `metric_id IN (...) AND season = ? AND week BETWEEN ? AND ?`, which is
 *     exactly the `idx_pws_metric_season_week` index.
 *  2. `base` computes each column from those sums, so rates are recomputed over
 *     the range rather than averaged, and applies the games floor. When
 *     scored, it left-joins `fsum` so a player with games but no scoring
 *     stats gets zero points rather than null.
 *  3. `scored` flags each player as qualified or not (`q`), per the spec's
 *     qualifiers.
 *  4. `ranked`, only when percentiles are requested, adds positional
 *     percentiles among qualified players. It runs before the user's filters
 *     so that narrowing the view never changes anyone's percentile.
 *  5. The outer query drops unqualified players (unless asked not to),
 *     filters, sorts with NULLs last, and pages.
 *
 * Safety: identifiers in the SQL are only fixed text and index-derived aliases
 * (`k0`, `v3`, `p3`). Every value, including metric ids and scoring weights, is
 * a bound `?`.
 *
 * Percentiles use `PERCENT_RANK`, which needs SQLite 3.25+. That's satisfied by
 * both `androidx.sqlite:sqlite-bundled` and the platform SQLite on the target
 * device.
 */
public object StatQueryBuilder {

    public const val DEFAULT_SEARCH_LIMIT: Int = 20

    public fun grid(spec: StatQuerySpec): GridQuery {
        // Displayed columns first, so their alias index equals display order.
        val plan = Plan(
            spec.columns + spec.sort.map { it.column } + spec.filters.map { it.column } +
                spec.qualifiers.map { it.column },
        )
        val w = SqlWriter()

        w.aggregateAndBase(spec, plan)
        w.scored(spec, plan)
        val source = if (spec.percentiles) {
            w.ranked(spec, plan)
            "ranked"
        } else {
            "scored"
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
        val plan = Plan(spec.filters.map { it.column } + spec.qualifiers.map { it.column })
        val w = SqlWriter()
        w.aggregateAndBase(spec, plan)
        w.scored(spec, plan)
        w.line("SELECT COUNT(*)")
        w.line("FROM scored")
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

    /**
     * Name, position and team for [ids], in no particular order. Null when
     * [ids] is empty. Result columns: player_id, full_name, position, team.
     */
    public fun players(ids: Collection<String>): SqlQuery? {
        val distinct = ids.distinct().sorted()
        if (distinct.isEmpty()) return null
        require(distinct.size <= StatQuerySpec.MAX_LIMIT) { "at most ${StatQuerySpec.MAX_LIMIT} ids" }
        val w = SqlWriter()
        w.line("SELECT player_id, full_name, position, team")
        w.line("FROM player")
        w.line("WHERE player_id IN (${distinct.joinToString(", ") { w.text(it) }})")
        return w.build()
    }
}

/** The columns a query computes and the components they need, with stable aliases. */
private class Plan(columns: List<StatColumn>) {
    val columns: List<StatColumn> = columns.distinct()

    /** Whether the scoring step (`wk`, `fw`, `fsum`) runs. */
    val scored: Boolean = this.columns.any { it.isFantasy }

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
        ScoredOutput.entries.firstOrNull { it.pseudo == component }?.let {
            // Played but scored nothing: zero points, not unknown.
            return "COALESCE(fsum.${it.alias}, 0)"
        }
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
        if (plan.scored) scoring(spec, checkNotNull(spec.scoring))
        line(if (plan.scored) ", agg AS (" else "WITH agg AS (")
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
        if (plan.scored) line("  LEFT JOIN fsum ON fsum.player_id = agg.player_id")
        line("  WHERE ${plan.games} >= ${int(spec.minGames)}")
        line(")")
    }

    /**
     * Points under [profile]: `wk` pivots actual components per week (bonuses
     * are per-game, so they need weekly granularity), `we` pivots expected
     * components as one range-total per player (no bonus has an expectation,
     * so xFP is just a linear sum and never needs a weekly breakdown). `fw`
     * scores each actual week; `xf` scores each player's expected total
     * directly. `fsum` combines both sides for every player either touched,
     * zero-filling whichever side (if either) a player has no facts for.
     *
     * Split rather than one shared pivot: every scoring component together is
     * wide enough (~40 columns) that a single scan paid for every column's
     * branch on every one of its rows, which dominated this step's cost on a
     * full season (measured ~190ms). Actual and expected ids are disjoint, so
     * splitting the pivot in two — 25 actual columns over actual-only rows,
     * 15 expected columns over expected-only rows — roughly halves the total
     * row×column work (measured ~100ms combined).
     */
    fun scoring(spec: StatQuerySpec, profile: ScoringProfile) {
        val wActual = { c: Component -> "COALESCE(wk.w${ACTUAL_COMPONENTS.indexOf(c)}, 0)" }
        val wExpected = { c: Component -> "COALESCE(we.e${EXPECTED_COMPONENTS.indexOf(c)}, 0)" }
        line("WITH wk AS (")
        line("  SELECT s.player_id, s.week")
        ACTUAL_COMPONENTS.forEachIndexed { i, c ->
            line("       , SUM(s.value) FILTER (WHERE s.metric_id = ${text(c.id)}) AS w$i")
        }
        line("  FROM player_week_stat s")
        line("  WHERE s.metric_id IN (${ACTUAL_COMPONENTS.joinToString(", ") { text(it.id) }})")
        line("    AND s.season = ${int(spec.season)}")
        line("    AND s.week BETWEEN ${int(spec.weeks.first)} AND ${int(spec.weeks.last)}")
        line("  GROUP BY s.player_id, s.week")
        line("), we AS (")
        line("  SELECT s.player_id")
        EXPECTED_COMPONENTS.forEachIndexed { i, c ->
            line("       , SUM(s.value) FILTER (WHERE s.metric_id = ${text(c.id)}) AS e$i")
        }
        line("  FROM player_week_stat s")
        line("  WHERE s.metric_id IN (${EXPECTED_COMPONENTS.joinToString(", ") { text(it.id) }})")
        line("    AND s.season = ${int(spec.season)}")
        line("    AND s.week BETWEEN ${int(spec.weeks.first)} AND ${int(spec.weeks.last)}")
        line("  GROUP BY s.player_id")
        line("), fw AS (")
        line("  SELECT wk.player_id")
        line("       , ${points(profile, expected = false, wActual)} AS fp")
        line("  FROM wk")
        line("  JOIN player p ON p.player_id = wk.player_id")
        line("), xf AS (")
        line("  SELECT we.player_id")
        line("       , ${points(profile, expected = true, wExpected)} AS xfp")
        line("  FROM we")
        line("  JOIN player p ON p.player_id = we.player_id")
        line("), players_scored AS (")
        line("  SELECT player_id FROM wk")
        line("  UNION")
        line("  SELECT player_id FROM we")
        line("), fsum AS (")
        line("  SELECT players_scored.player_id")
        line("       , COALESCE(fp_agg.fp, 0) AS fp")
        line("       , COALESCE(xf.xfp, 0) AS xfp")
        line("       , COALESCE(fp_agg.fp, 0) - COALESCE(xf.xfp, 0) AS oe")
        line("  FROM players_scored")
        line("  LEFT JOIN (SELECT player_id, SUM(fp) AS fp FROM fw GROUP BY player_id) fp_agg")
        line("    ON fp_agg.player_id = players_scored.player_id")
        line("  LEFT JOIN xf ON xf.player_id = players_scored.player_id")
        line(")")
    }

    /**
     * One week's points. Every weight is bound, including zeros, so the SQL
     * shape depends only on the number of bonuses.
     */
    private fun points(profile: ScoringProfile, expected: Boolean, w: (Component) -> String): String {
        val terms = mutableListOf<String>()
        for (rule in ScoringRule.entries) {
            val inputs = RULE_INPUTS.getValue(rule)
            for (term in if (expected) inputs.expected else inputs.actual) {
                val weight = if (rule == ScoringRule.RECEPTION) {
                    receptionWeight(profile)
                } else {
                    real(profile.weight(rule) * term.sign)
                }
                terms += "$weight * ${w(term.component)}"
            }
        }
        if (!expected) {
            // Bonuses have no expectation; they only ever add to actual points.
            for (bonus in profile.yardageBonuses) {
                val yards = BONUS_INPUTS.getValue(bonus.stat).joinToString(" + ", "(", ")") { w(it) }
                val lower = int(bonus.min)
                val upper = bonus.maxExclusive?.let { " AND $yards < ${int(it)}" }.orEmpty()
                val points = real(bonus.points)
                terms += "CASE WHEN $yards >= $lower$upper THEN $points ELSE 0 END"
            }
        }
        return terms.joinToString(" + ", "(", ")")
    }

    /** Reception points by position: the TE-premium case. */
    private fun receptionWeight(profile: ScoringProfile): String {
        val rb = text(Position.RB.code)
        val rbPoints = real(profile.receptionWeight(Position.RB))
        val wr = text(Position.WR.code)
        val wrPoints = real(profile.receptionWeight(Position.WR))
        val te = text(Position.TE.code)
        val tePoints = real(profile.receptionWeight(Position.TE))
        val otherPoints = real(profile.weight(ScoringRule.RECEPTION))
        return "(CASE p.position WHEN $rb THEN $rbPoints WHEN $wr THEN $wrPoints " +
            "WHEN $te THEN $tePoints ELSE $otherPoints END)"
    }

    /** Flags each player 1 if they meet every qualifier, else 0. */
    fun scored(spec: StatQuerySpec, plan: Plan) {
        val q = if (spec.qualifiers.isEmpty()) {
            "1"
        } else {
            "CASE WHEN " + spec.qualifiers.joinToString(" AND ") { condition(it, plan) } + " THEN 1 ELSE 0 END"
        }
        line(", scored AS (")
        line("  SELECT base.*, $q AS q")
        line("  FROM base")
        line(")")
    }

    fun ranked(spec: StatQuerySpec, plan: Plan) {
        line(", ranked AS (")
        line("  SELECT scored.*")
        for (column in spec.columns) {
            val i = plan.index(column)
            // Best = 1.0, ranked only among qualified players with a value.
            // Partitioning on that keeps everyone else from diluting the ranks.
            val order = if (column.higherIsBetter) "ASC" else "DESC"
            val unranked = "(v$i IS NULL OR q = 0)"
            line(
                "       , CASE WHEN $unranked THEN NULL ELSE PERCENT_RANK() OVER " +
                    "(PARTITION BY position, $unranked ORDER BY v$i $order) END AS p$i",
            )
        }
        line("  FROM scored")
        line(")")
    }

    fun condition(filter: Filter, plan: Plan): String {
        val v = "v${plan.index(filter.column)}"
        return when (val c = filter.condition) {
            is Condition.AtLeast -> "$v >= ${real(c.value)}"
            is Condition.AtMost -> "$v <= ${real(c.value)}"
            is Condition.GreaterThan -> "$v > ${real(c.value)}"
            is Condition.LessThan -> "$v < ${real(c.value)}"
            is Condition.Between -> "$v BETWEEN ${real(c.min)} AND ${real(c.max)}"
        }
    }

    fun where(spec: StatQuerySpec, plan: Plan) {
        val conditions = mutableListOf<String>()
        if (!spec.includeUnqualified) conditions += "q = 1"
        if (spec.positions.isNotEmpty()) {
            val codes = spec.positions.sortedBy { it.ordinal }
            conditions += "position IN (${codes.joinToString(", ") { text(it.code) }})"
        }
        if (spec.teams.isNotEmpty()) {
            conditions += "team IN (${spec.teams.sorted().joinToString(", ") { text(it) }})"
        }
        if (spec.playerIds.isNotEmpty()) {
            conditions += "player_id IN (${spec.playerIds.sorted().joinToString(", ") { text(it) }})"
        }
        spec.name?.let(::normalizeSearch)?.takeIf { it.isNotEmpty() }?.let { q ->
            conditions += nameMatch(q)
        }
        for (filter in spec.filters) conditions += condition(filter, plan)
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
