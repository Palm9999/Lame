package dev.gridiron.core.statquery

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.ScoringTier

/**
 * [SCORING_COMPONENTS] split by whether a rule input is actual or expected
 * (disjoint: every id is `x_`-prefixed in one set and not in the other). The
 * `scoring()` CTEs pivot each separately, so a row only ever pays for the
 * branches of the set it belongs to, not all of [SCORING_COMPONENTS].
 */
/** The offense's rules, in declaration order so equal profiles give identical SQL. */
private val OFFENSE_RULES: List<ScoringRule> = ScoringRule.entries.filter { it !in SPECIAL_RULES }

private val SPECIAL_RULE_LIST: List<ScoringRule> = ScoringRule.entries.filter { it in SPECIAL_RULES }

private val ACTUAL_COMPONENTS: List<Component> =
    (OFFENSE_RULES.flatMap { RULE_INPUTS.getValue(it).actual }.map { it.component } + BONUS_INPUTS.values.flatten())
        .distinct()
        .sortedBy { it.id }

/** Kicking and team-defense inputs, and points and yards allowed for the tiers: pivoted apart (`ws`), so the offense's pivot stays as narrow as it was. */
private val SPECIAL_COMPONENTS: List<Component> =
    (SPECIAL_RULE_LIST.flatMap { RULE_INPUTS.getValue(it).actual }.map { it.component } + Components.POINTS_ALLOWED + Components.YARDS_ALLOWED)
        .distinct()
        .sortedBy { it.id }

/** What the rollup path reads weekly: the components a yardage bonus or a tier is decided on in one game. */
private val BONUS_COMPONENTS: List<Component> = BONUS_INPUTS.values.flatten().distinct().sortedBy { it.id }

private val TIER_COMPONENTS: List<Component> = listOf(Components.POINTS_ALLOWED, Components.YARDS_ALLOWED).sortedBy { it.id }

private val EXPECTED_COMPONENTS: List<Component> =
    RULE_INPUTS.values.flatMap { it.expected }.map { it.component }.distinct().sortedBy { it.id }

/**
 * Turns a [StatQuerySpec] into SQL over the ETL's long/narrow fact table.
 *
 * Query shape:
 *
 *  0. When a fantasy column is planned, `wk` pivots the offense's scoring
 *     components to one row per player-week, `ws` does the same for kicking,
 *     team-defense and points-allowed components, `fw` and `fs` apply the
 *     spec's scoring profile to each week (so per-game bonuses and
 *     points- and yards-allowed tiers see single games), and `fsum` totals them per
 *     player into fantasy points, expected fantasy points and FPOE. When the
 *     spec's weeks equal a rollup window, `ra`, `rs` and `re` weight the window's
 *     sums instead, and only the yardage bonuses and tiers (`bw`, `tw`, summed as
 *     `fb`) read weekly facts, since one game decides them.
 *  1. `agg` pivots `player_week_stat` to one row per player, summing only the
 *     components the requested columns need. Its predicate is
 *     `metric_id IN (...) AND season = ? AND week BETWEEN ? AND ?`, which is
 *     exactly the `idx_pws_metric_season_week` index. When the spec's weeks equal
 *     one of its [StatQuerySpec.rollups],
 *     it pivots `player_window_stat` instead (`... AND window = ?`, a seek on its
 *     metric-first primary key, guarded by `window_def` so a window whose bounds moved reads nothing): the same sums, already added up.
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
            w.line(
                when {
                    spec.ranks -> "     , v$i, p$i, r$i, n$i"
                    spec.percentiles -> "     , v$i, p$i"
                    else -> "     , v$i"
                },
            )
        }
        w.line("FROM $source")
        w.where(spec, plan)
        w.orderBy(spec, plan)
        w.line("LIMIT ${w.int(spec.limit)} OFFSET ${w.int(spec.offset)}")

        return GridQuery(w.build(), GridLayout(spec.columns, spec.percentiles, spec.ranks))
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
        val rollup = spec.rollups.firstOrNull { it.weeks == spec.weeks }
        if (plan.scored) {
            val profile = checkNotNull(spec.scoring)
            if (rollup != null) rollupScoring(spec, profile, rollup) else scoring(spec, profile)
        }
        line(if (plan.scored) ", agg AS (" else "WITH agg AS (")
        line("  SELECT s.player_id")
        plan.components.forEachIndexed { i, c ->
            line("       , SUM(CASE WHEN s.metric_id = ${text(c.id)} THEN s.value END) AS k$i")
        }
        if (rollup != null) {
            line("  FROM player_window_stat s")
            line("  WHERE s.metric_id IN (${plan.components.joinToString(", ") { text(it.id) }})")
            rollupFilter(spec, rollup)
        } else {
            line("  FROM player_week_stat s")
            line("  WHERE s.metric_id IN (${plan.components.joinToString(", ") { text(it.id) }})")
            line("    AND s.season = ${int(spec.season)}")
            line("    AND s.week BETWEEN ${int(spec.weeks.first)} AND ${int(spec.weeks.last)}")
        }
        line("  GROUP BY s.player_id")
        line("), base AS (")
        line("  SELECT p.player_id, p.full_name, p.position, p.team, p.search_name, ${plan.games} AS games")
        plan.columns.forEachIndexed { i, column ->
            line("       , ${plan.valueExpr(column, spec.mode)} AS v$i")
        }
        line("  FROM agg")
        line("  JOIN player p ON p.player_id = agg.player_id")
        if (plan.scored) line("  LEFT JOIN fsum ON fsum.player_id = agg.player_id")
        line("  WHERE ${plan.games} >= ${int(spec.minGames)}" + alwaysShowClause(spec, "p.player_id", " OR "))
        line(")")
    }

    /** Season, window and the `window_def` guard: a window whose bounds moved in the open database reads nothing, not another range. */
    private fun rollupFilter(spec: StatQuerySpec, rollup: RollupWindow) {
        line("    AND s.season = ${int(spec.season)}")
        line("    AND s.window = ${text(rollup.window)}")
        line("    AND EXISTS (SELECT 1 FROM window_def d WHERE d.season = ${int(spec.season)} AND d.window = ${text(rollup.window)}")
        line("                AND d.first_week = ${int(rollup.weeks.first)} AND d.last_week = ${int(rollup.weeks.last)})")
    }

    private fun pivot(table: String, components: List<Component>, prefix: String, byWeek: Boolean) {
        line(if (byWeek) "  SELECT s.player_id, s.week" else "  SELECT s.player_id")
        components.forEachIndexed { i, c ->
            line("       , SUM(s.value) FILTER (WHERE s.metric_id = ${text(c.id)}) AS $prefix$i")
        }
        line("  FROM $table s")
        line("  WHERE s.metric_id IN (${components.joinToString(", ") { text(it.id) }})")
    }

    /**
     * [scoring]'s results from a rollup window. Everything linear in a stat (every rule,
     * expected points, the kicking and team-defense rules) is weighted from the window's
     * sums: `ra`, `rs` and `re`. Only what a single game decides stays weekly: yardage
     * bonuses (`bw`) and points- and yards-allowed tiers (`tw`), summed per player as `fb`.
     * `fsum` has the same columns as [scoring]'s, so a profile with neither reads no weekly row.
     */
    fun rollupScoring(spec: StatQuerySpec, profile: ScoringProfile, rollup: RollupWindow) {
        val wActual = { c: Component -> "COALESCE(ra.w${ACTUAL_COMPONENTS.indexOf(c)}, 0)" }
        val wSpecial = { c: Component -> "COALESCE(rs.s${SPECIAL_COMPONENTS.indexOf(c)}, 0)" }
        val wExpected = { c: Component -> "COALESCE(re.e${EXPECTED_COMPONENTS.indexOf(c)}, 0)" }
        val wBonus = { c: Component -> "COALESCE(bw.b${BONUS_COMPONENTS.indexOf(c)}, 0)" }
        val hasBonus = profile.yardageBonuses.isNotEmpty()
        val hasTiers = profile.pointsAllowedTiers.isNotEmpty() || profile.yardsAllowedTiers.isNotEmpty()

        line("WITH ra AS (")
        pivot("player_window_stat", ACTUAL_COMPONENTS, "w", byWeek = false)
        rollupFilter(spec, rollup)
        line("  GROUP BY s.player_id")
        line("), rs AS (")
        pivot("player_window_stat", SPECIAL_COMPONENTS, "s", byWeek = false)
        rollupFilter(spec, rollup)
        line("  GROUP BY s.player_id")
        line("), re AS (")
        pivot("player_window_stat", EXPECTED_COMPONENTS, "e", byWeek = false)
        rollupFilter(spec, rollup)
        line("  GROUP BY s.player_id")
        val weekly = mutableListOf<String>()
        if (hasBonus) {
            line("), bw AS (")
            pivot("player_week_stat", BONUS_COMPONENTS, "b", byWeek = true)
            line("    AND s.season = ${int(spec.season)}")
            line("    AND s.week BETWEEN ${int(spec.weeks.first)} AND ${int(spec.weeks.last)}")
            line("  GROUP BY s.player_id, s.week")
            weekly += "bw"
        }
        if (hasTiers) {
            line("), tw AS (")
            pivot("player_week_stat", TIER_COMPONENTS, "t", byWeek = true)
            line("    AND s.season = ${int(spec.season)}")
            line("    AND s.week BETWEEN ${int(spec.weeks.first)} AND ${int(spec.weeks.last)}")
            line("  GROUP BY s.player_id, s.week")
            weekly += "tw"
        }
        if (weekly.isNotEmpty()) {
            line("), fb AS (")
            line("  SELECT player_id, SUM(fp) AS fp")
            line("  FROM (")
            val parts = mutableListOf<String>()
            if (hasBonus) parts += "    SELECT player_id, ${bonusTerms(profile, wBonus).joinToString(" + ")} AS fp FROM bw"
            if (hasTiers) {
                val pa = tiers(profile.pointsAllowedTiers, "tw.t${TIER_COMPONENTS.indexOf(Components.POINTS_ALLOWED)}")
                val ya = tiers(profile.yardsAllowedTiers, "tw.t${TIER_COMPONENTS.indexOf(Components.YARDS_ALLOWED)}")
                parts += "    SELECT player_id, $pa + $ya AS fp FROM tw"
            }
            line(parts.joinToString("\n    UNION ALL\n"))
            line("  )")
            line("  GROUP BY player_id")
        }
        line("), players_scored AS (")
        line("  SELECT player_id FROM ra UNION SELECT player_id FROM rs UNION SELECT player_id FROM re" + if (weekly.isNotEmpty()) " UNION SELECT player_id FROM fb" else "")
        line("), fsum AS (")
        line("  SELECT t.player_id, t.fp AS fp, t.xfp AS xfp, t.fp - t.xfp AS oe")
        line("  FROM (")
        line("    SELECT u.player_id")
        line("         , ${points(profile, OFFENSE_RULES, expected = false, bonuses = false, wActual)} + ${points(profile, SPECIAL_RULE_LIST, expected = false, bonuses = false, wSpecial)}" + (if (weekly.isNotEmpty()) " + COALESCE(fb.fp, 0)" else "") + " AS fp")
        line("         , ${points(profile, OFFENSE_RULES, expected = true, bonuses = false, wExpected)} AS xfp")
        line("    FROM players_scored u")
        line("    JOIN player p ON p.player_id = u.player_id")
        line("    LEFT JOIN ra ON ra.player_id = u.player_id")
        line("    LEFT JOIN rs ON rs.player_id = u.player_id")
        line("    LEFT JOIN re ON re.player_id = u.player_id")
        if (weekly.isNotEmpty()) line("    LEFT JOIN fb ON fb.player_id = u.player_id")
        line("  ) t")
        line(")")
    }

    /**
     * Points under [profile]: `wk` pivots actual components per week (bonuses
     * are per-game, so they need weekly granularity), `we` pivots expected
     * components as one range-total per player (no bonus has an expectation,
     * so xFP is just a linear sum and never needs a weekly breakdown). `ws`
     * pivots kicking, team-defense and points-allowed components the same way
     * as `wk`, and `fs` scores them with each week's tier. `fw`
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
        val wSpecial = { c: Component -> "COALESCE(ws.s${SPECIAL_COMPONENTS.indexOf(c)}, 0)" }
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
        line("), ws AS (")
        line("  SELECT s.player_id, s.week")
        SPECIAL_COMPONENTS.forEachIndexed { i, c ->
            line("       , SUM(s.value) FILTER (WHERE s.metric_id = ${text(c.id)}) AS s$i")
        }
        line("  FROM player_week_stat s")
        line("  WHERE s.metric_id IN (${SPECIAL_COMPONENTS.joinToString(", ") { text(it.id) }})")
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
        line("       , ${points(profile, OFFENSE_RULES, expected = false, bonuses = true, wActual)} AS fp")
        line("  FROM wk")
        line("  JOIN player p ON p.player_id = wk.player_id")
        line("), fs AS (")
        line("  SELECT ws.player_id")
        line("       , ${points(profile, SPECIAL_RULE_LIST, expected = false, bonuses = false, wSpecial)} + ${tiers(profile.pointsAllowedTiers, "ws.s${SPECIAL_COMPONENTS.indexOf(Components.POINTS_ALLOWED)}")} + ${tiers(profile.yardsAllowedTiers, "ws.s${SPECIAL_COMPONENTS.indexOf(Components.YARDS_ALLOWED)}")} AS fp")
        line("  FROM ws")
        line("), xf AS (")
        line("  SELECT we.player_id")
        line("       , ${points(profile, OFFENSE_RULES, expected = true, bonuses = false, wExpected)} AS xfp")
        line("  FROM we")
        line("  JOIN player p ON p.player_id = we.player_id")
        line("), players_scored AS (")
        line("  SELECT player_id FROM wk")
        line("  UNION")
        line("  SELECT player_id FROM we")
        line("  UNION")
        line("  SELECT player_id FROM ws")
        line("), fsum AS (")
        line("  SELECT players_scored.player_id")
        line("       , COALESCE(fp_agg.fp, 0) AS fp")
        line("       , COALESCE(xf.xfp, 0) AS xfp")
        line("       , COALESCE(fp_agg.fp, 0) - COALESCE(xf.xfp, 0) AS oe")
        line("  FROM players_scored")
        line("  LEFT JOIN (SELECT player_id, SUM(fp) AS fp")
        line("             FROM (SELECT player_id, fp FROM fw UNION ALL SELECT player_id, fp FROM fs)")
        line("             GROUP BY player_id) fp_agg")
        line("    ON fp_agg.player_id = players_scored.player_id")
        line("  LEFT JOIN xf ON xf.player_id = players_scored.player_id")
        line(")")
    }

    /**
     * One week's points from [rules]. Every weight is bound, including zeros,
     * so the SQL shape depends only on the number of bonuses and tiers.
     */
    private fun points(profile: ScoringProfile, rules: List<ScoringRule>, expected: Boolean, bonuses: Boolean, w: (Component) -> String): String {
        val terms = mutableListOf<String>()
        for (rule in rules) {
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
        if (bonuses) terms += bonusTerms(profile, w)
        return terms.joinToString(" + ", "(", ")")
    }

    /** One week's yardage-bonus points. Bonuses have no expectation; they only ever add to the offense's actual points. */
    private fun bonusTerms(profile: ScoringProfile, w: (Component) -> String): List<String> =
        profile.yardageBonuses.map { bonus ->
            val yards = BONUS_INPUTS.getValue(bonus.stat).joinToString(" + ", "(", ")") { w(it) }
            val lower = int(bonus.min)
            val upper = bonus.maxExclusive?.let { " AND $yards < ${int(it)}" }.orEmpty()
            val points = real(bonus.points)
            "CASE WHEN $yards >= $lower$upper THEN $points ELSE 0 END"
        }

    /**
     * One week's tier from a profile's own [tiers] for the pivot column [value],
     * checked highest first. A week with none (a kicker's) scores none: the
     * pivot's column is NULL there, while a shutout stores 0.
     */
    private fun tiers(tiers: List<ScoringTier>, value: String): String {
        if (tiers.isEmpty()) return "0"
        val cases = tiers.asReversed().joinToString(" ") { "WHEN $value >= ${int(it.min)} THEN ${real(it.points)}" }
        return "(CASE WHEN $value IS NULL THEN 0 $cases ELSE 0 END)"
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
        val met = spec.qualifiers.joinToString(" AND ") { condition(it, plan) }
        // A player let in by alwaysShow below the games floor is never ranked.
        val floor = if (spec.alwaysShow.isNotEmpty() && spec.minGames > 1) "games >= ${int(spec.minGames)}" else null
        val q = when {
            floor == null && met.isEmpty() -> "1"
            else -> "CASE WHEN " + listOfNotNull(floor, met.ifEmpty { null }).joinToString(" AND ") + " THEN 1 ELSE 0 END"
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
            if (spec.ranks) {
                // Place 1 = best (the percentile's order, reversed); ties share the better place.
                val best = if (column.higherIsBetter) "DESC" else "ASC"
                line(
                    "       , CASE WHEN $unranked THEN NULL ELSE RANK() OVER " +
                        "(PARTITION BY position, $unranked ORDER BY v$i $best) END AS r$i",
                )
                line(
                    "       , CASE WHEN $unranked THEN NULL ELSE COUNT(*) OVER " +
                        "(PARTITION BY position, $unranked) END AS n$i",
                )
            }
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

    /** `<joiner>id IN (…)` for [StatQuerySpec.alwaysShow], or nothing. */
    private fun alwaysShowClause(spec: StatQuerySpec, id: String, joiner: String): String =
        if (spec.alwaysShow.isEmpty()) "" else "$joiner$id IN (${spec.alwaysShow.sorted().joinToString(", ") { text(it) }})"

    fun where(spec: StatQuerySpec, plan: Plan) {
        val conditions = mutableListOf<String>()
        if (!spec.includeUnqualified) conditions += "(q = 1" + alwaysShowClause(spec, "player_id", " OR ") + ")"
        if (spec.positions.isNotEmpty()) {
            val codes = spec.positions.sortedBy { it.ordinal }
            conditions += "position IN (${codes.joinToString(", ") { text(it.code) }})"
        }
        if (spec.excludedPositions.isNotEmpty()) {
            val codes = spec.excludedPositions.sortedBy { it.ordinal }
            conditions += "(position IS NULL OR position NOT IN (${codes.joinToString(", ") { text(it.code) }}))"
        }
        if (spec.teams.isNotEmpty()) {
            conditions += "team IN (${spec.teams.sorted().joinToString(", ") { text(it) }})"
        }
        if (spec.playerIds.isNotEmpty()) {
            conditions += "player_id IN (${spec.playerIds.sorted().joinToString(", ") { text(it) }})"
        }
        if (spec.excludedPlayerIds.isNotEmpty()) {
            conditions += "player_id NOT IN (${spec.excludedPlayerIds.sorted().joinToString(", ") { text(it) }})"
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
