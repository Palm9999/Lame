package dev.gridiron.core.forecast

import dev.gridiron.core.model.ScoringPresets
import java.util.Locale
import kotlin.math.ln

/** A kicker or D/ST placed on a team as of one week: what each of its games' projections starts from. */
private sealed interface TeamUnit {
    val player: PlayerInfo
    val team: String
}

private class Kicker(override val player: PlayerInfo, override val team: String, val rates: KickerRates, val usualPoints: Double) : TeamUnit

private class Defense(
    override val player: PlayerInfo,
    override val team: String,
    val own: Map<String, Double>,
    val ownAllowed: Double,
) : TeamUnit

/**
 * One unit's projection for one game, stage by stage, with the waterfall's
 * notes (null: no such factor). [sd] is a D/ST's points-allowed spread; a
 * kicker's is 0 (he has no points allowed).
 */
private class UnitStages(
    val baseline: Map<String, Double>,
    val afterMatchup: Map<String, Double>,
    val final: Map<String, Double>,
    val matchupNote: String?,
    val scriptNote: String?,
    val sd: Double = 0.0,
)

/**
 * Kickers and team defenses (spec §6), week by week beside the [Projector]'s
 * players and, like them, only from the games before each week. Past weeks
 * keep their final projection; the upcoming week keeps both stages and the
 * factors; every game from the upcoming week on is summed into rest of season.
 */
internal class UnitProjector(
    private val inputs: ForecastInputs,
    private val gameOf: Map<Triple<String, Int, Int>, Game>,
    private val sink: ProjectionSink,
) {
    private val chronological: List<PlayerGame> = inputs.unitHistory.values.flatten().sortedBy { it.order }
    private val kickers: List<PlayerInfo> = inputs.units.values.filter { it.position == "K" }.sortedBy { it.playerId }
    private var upcoming: Pair<UnitWeek, List<TeamUnit>>? = null

    /** Projects one week's units; for the upcoming week, also starts rest of season. */
    fun week(season: Int, week: Int, kind: WeekKind, ros: MutableMap<Pair<String, String>, DoubleArray>) {
        val state = UnitWeek(season, week)
        val units = prepare(state, kind)
        for (u in units) {
            val game = gameOf[Triple(u.team, season, week)] ?: continue // an upcoming bye: rest of season only
            val stages = stages(state, u, game)
            val id = u.player.playerId
            when (kind) {
                WeekKind.PAST -> if (points(stages.final, stages.sd) >= K.PAST_WEEK_MIN_POINTS) emit(u, season, week, "final", stages.final, stages.sd)
                WeekKind.UPCOMING -> if (points(stages.final, stages.sd) >= K.UPCOMING_MIN_POINTS) {
                    emit(u, season, week, "baseline", stages.baseline, stages.sd)
                    emit(u, season, week, "final", stages.final, stages.sd)
                    stages.matchupNote?.let { sink.factor(id, season, week, "matchup", logRatio(stages.afterMatchup, stages.baseline, stages.sd), it) }
                    stages.scriptNote?.let { sink.factor(id, season, week, "game_script", logRatio(stages.final, stages.afterMatchup, stages.sd), it) }
                }
                WeekKind.REST -> Unit
            }
        }
        if (kind == WeekKind.UPCOMING) {
            upcoming = state to units
            for (u in units) addRest(state, u, season, week, ros)
        }
    }

    /** Adds a week after the upcoming one to rest of season, from what was known as of the upcoming week. */
    fun rest(season: Int, week: Int, ros: MutableMap<Pair<String, String>, DoubleArray>) {
        val (state, units) = upcoming ?: return
        for (u in units) addRest(state, u, season, week, ros)
    }

    /** What every unit's projection for one week shares, from the games before it. */
    private inner class UnitWeek(val season: Int, val week: Int) {
        val order = order(season, week)
        private val before = chronological.takeWhile { it.order < order }
        private val kicks = before.filter { position(it) == "K" }
        private val defenses = before.filter { position(it) == "DST" }
        val kickLeague: KickLeague? = kickLeague(kicks)
        val attempts: AttemptFit? = AttemptFit.fit(teamKicks(kicks))
        val defenseLeague: DefenseLeague? = defenseLeague(defenses)
        val sd: Double = paSpread(
            defenses.mapNotNull { g -> opponentOf(g)?.let { (opponent, game) -> game.impliedPoints(opponent)?.let { g["points_allowed"] - it } } },
        )
        val leagueImplied: Double = averageImplied(inputs.games, season)

        /** Per offense, oldest first, this season and last: the points it scored, and each D/ST stat defenses got against it. */
        val scored = HashMap<String, MutableList<Double>>()
        val allowed = HashMap<String, HashMap<String, MutableList<Double>>>()

        init {
            for (g in defenses) {
                if (g.season < season - 1) continue
                val (offense, _) = opponentOf(g) ?: continue
                scored.getOrPut(offense) { ArrayList() } += g["points_allowed"]
                val byStat = allowed.getOrPut(offense) { HashMap() }
                for (stat in DST_STATS) byStat.getOrPut(stat) { ArrayList() } += g[stat]
            }
        }
    }

    private fun position(g: PlayerGame): String? = inputs.units[g.playerId]?.position

    /** The team [g]'s unit played against, and the game. */
    private fun opponentOf(g: PlayerGame): Pair<String, Game>? = gameOf[Triple(g.team, g.season, g.week)]?.let { it.opponentOf(g.team) to it }

    /** Each team-game's kicks, with its implied points where a line was posted. */
    private fun teamKicks(kicks: List<PlayerGame>): List<TeamKicks> =
        kicks.groupBy { Triple(it.team, it.season, it.week) }.map { (key, games) ->
            TeamKicks(
                gameOf[key]?.impliedPoints(key.first),
                games.sumOf { g -> FG_BUCKETS.sumOf { g["fg_att_$it"] } },
                games.sumOf { it["xp_att"] },
            )
        }

    /** This week's kicker and D/ST for every team in the season's schedule (past weeks: only teams that played). */
    private fun prepare(state: UnitWeek, kind: WeekKind): List<TeamUnit> {
        val scheduled = inputs.games.filter { it.season == state.season }.flatMap { listOf(it.home, it.away) }.toSortedSet()
        val teams = if (kind == WeekKind.PAST) scheduled.filter { gameOf[Triple(it, state.season, state.week)] != null } else scheduled.toList()
        val units = ArrayList<TeamUnit>()
        val kickLeague = state.kickLeague
        if (kickLeague != null && state.attempts != null) {
            for ((team, k) in kickersFor(teams, state, kind)) units += kicker(state, k, team, kickLeague)
        }
        state.defenseLeague?.let { league -> for (team in teams) defense(state, team, league)?.let(units::add) }
        return units
    }

    /**
     * Each team's kicker this week, never one kicker for two teams. In a past
     * week, the kicker who kicked for the team that week; from the upcoming
     * week on, the kicker nflverse lists on the team. Otherwise, the one who
     * kicked for the team most recently, unless nflverse lists him elsewhere
     * now. Only kickers with a kick this season or last, before this week, count.
     */
    private fun kickersFor(teams: List<String>, state: UnitWeek, kind: WeekKind): Map<String, PlayerInfo> {
        val last = kickers.mapNotNull { k ->
            inputs.unitHistory[k.playerId].orEmpty().lastOrNull { it.order < state.order }
                ?.takeIf { it.season >= state.season - 1 }
                ?.let { k to it }
        }
        val picked = LinkedHashMap<String, PlayerInfo>()
        for (team in teams) {
            val first = if (kind == WeekKind.PAST) {
                last.firstOrNull { (k, _) ->
                    inputs.unitHistory[k.playerId].orEmpty().any { it.season == state.season && it.week == state.week && it.team == team }
                }?.first
            } else {
                last.filter { (k, _) -> k.team == team }.maxByOrNull { it.second.order }?.first
            }
            first?.let { picked[team] = it }
        }
        for (team in teams) {
            if (team in picked) continue
            last.filter { (k, g) -> g.team == team && k !in picked.values && (kind == WeekKind.PAST || k.team == null || k.team == team) }
                .maxByOrNull { it.second.order }
                ?.let { picked[team] = it.first }
        }
        return picked
    }

    private fun recentGames(player: PlayerInfo, state: UnitWeek): List<PlayerGame> =
        inputs.unitHistory[player.playerId].orEmpty().filter { it.order < state.order && it.season >= state.season - 1 }

    private fun kicker(state: UnitWeek, player: PlayerInfo, team: String, league: KickLeague): Kicker {
        val usual = teamPoints(state.scored[team].orEmpty(), state.defenseLeague?.pointsAllowed ?: state.leagueImplied)
        return Kicker(player, team, kickerRates(recentGames(player, state), league), usual)
    }

    private fun defense(state: UnitWeek, team: String, league: DefenseLeague): Defense? {
        val player = inputs.units["DST_$team"] ?: return null
        val own = recentGames(player, state)
        if (own.isEmpty()) return null
        return Defense(
            player, team,
            DST_STATS.associateWith { stat -> unitRate(own.map { it[stat] }, league.perGame.getValue(stat), K.DST_K.getValue(stat)) },
            unitRate(own.map { it["points_allowed"] }, league.pointsAllowed, K.DST_PA_K),
        )
    }

    private fun stages(state: UnitWeek, u: TeamUnit, game: Game): UnitStages = when (u) {
        is Kicker -> {
            val fit = checkNotNull(state.attempts)
            val baseline = kickStats(u.rates, fit.fieldGoals(u.usualPoints), fit.extraPoints(u.usualPoints))
            val implied = game.impliedPoints(u.team)
            val final = implied?.let { kickStats(u.rates, fit.fieldGoals(it), fit.extraPoints(it)) } ?: baseline
            UnitStages(baseline, baseline, final, matchupNote = null, scriptNote = implied?.let { impliedNote("Implied", it, state.leagueImplied) })
        }
        is Defense -> {
            val league = checkNotNull(state.defenseLeague)
            val opponent = game.opponentOf(u.team)
            val against = state.allowed[opponent].orEmpty()
            val factors = DST_STATS.associateWith { stat -> opponentFactor(against[stat].orEmpty(), league.perGame.getValue(stat)) }
            val opponentScores = unitRate(state.scored[opponent].orEmpty(), league.pointsAllowed, K.DST_PA_K)
            val implied = game.impliedPoints(opponent)
            val s = defenseStages(u.own, u.ownAllowed, factors, opponentScores, league, implied)
            UnitStages(
                s.baseline, s.afterMatchup, s.final,
                matchupNote = defenseNote(opponent, against, league),
                scriptNote = implied?.let { impliedNote("$opponent implied", it, state.leagueImplied) },
                sd = state.sd,
            )
        }
    }

    private fun emit(u: TeamUnit, season: Int, week: Int, stage: String, components: Map<String, Double>, sd: Double) {
        val cv = K.EMPIRICAL_CV.getValue(u.player.position)
        for ((metric, mean) in withGame(components)) {
            check(mean.isFinite()) { "${u.player.playerId}'s $season week $week $metric projection is $mean" }
            if (mean > 0.0) sink.projection(u.player.playerId, season, week, metric, stage, mean, variance(metric, mean, cv, sd))
        }
    }

    private fun addRest(state: UnitWeek, u: TeamUnit, season: Int, week: Int, ros: MutableMap<Pair<String, String>, DoubleArray>) {
        val game = gameOf[Triple(u.team, season, week)] ?: return // a bye
        val stages = stages(state, u, game)
        if (points(stages.final, stages.sd) < K.UPCOMING_MIN_POINTS) return
        val cv = K.EMPIRICAL_CV.getValue(u.player.position)
        for ((metric, mean) in withGame(stages.final)) {
            if (mean <= 0.0) continue
            val sum = ros.getOrPut(u.player.playerId to metric) { DoubleArray(2) }
            sum[0] += mean
            sum[1] += variance(metric, mean, cv, stages.sd)
        }
    }

    /**
     * A D/ST's projection carries `g` = 1, so rest of season (a sum) counts its
     * games and the phone can score each game's points-allowed tier.
     */
    private fun withGame(components: Map<String, Double>): Map<String, Double> =
        if (POINTS_ALLOWED in components) components + ("g" to 1.0) else components

    /**
     * Points allowed are about Normal(mean, [sd]): the variance is the spread
     * squared. A game count is exact. Everything else follows layer 7.
     */
    private fun variance(metric: String, mean: Double, cv: Double, sd: Double): Double = when (metric) {
        POINTS_ALLOWED -> sd * sd
        "g" -> 0.0
        else -> varianceFor(mean, cv)
    }

    /**
     * A unit's reference points for one game: its stats at the presets' values
     * and, for a D/ST, the preset tiers' expected points for its points allowed.
     */
    private fun points(components: Map<String, Double>, sd: Double): Double =
        referencePoints(components) +
            (components[POINTS_ALLOWED]?.let { ScoringPresets.PPR.expectedPointsAllowedPoints(it, sd) } ?: 0.0)

    private fun logRatio(after: Map<String, Double>, before: Map<String, Double>, sd: Double): Double {
        val a = points(after, sd)
        val b = points(before, sd)
        return if (a > 0.0 && b > 0.0) ln(a / b) else 0.0
    }
}

/** "Implied 27.5 pts (+4.3)", or "KC implied 27.5 pts (+4.3)" for a D/ST's opponent. */
private fun impliedNote(label: String, implied: Double, league: Double): String =
    String.format(Locale.US, "%s %.1f pts (%+.1f)", label, implied, implied - league)

/** "vs KC: gives up 2.9 sacks, 1.6 turnovers a game", shrunk like the matchup's factors. */
private fun defenseNote(opponent: String, against: Map<String, List<Double>>, league: DefenseLeague): String {
    fun rate(stat: String) = unitRate(against[stat].orEmpty(), league.perGame.getValue(stat), K.DST_OPP_K)
    return String.format(
        Locale.US, "vs %s: gives up %.1f sacks, %.1f turnovers a game",
        opponent, rate("dst_sacks"), rate("dst_interceptions") + rate("dst_fumble_recoveries"),
    )
}
