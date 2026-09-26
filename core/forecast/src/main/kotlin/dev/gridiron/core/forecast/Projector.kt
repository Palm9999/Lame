package dev.gridiron.core.forecast

import kotlin.math.ln

internal class ProjectionOutcome(val status: String, val upcoming: Map<Int, Int>, val weeks: Int)

private enum class WeekKind { PAST, UPCOMING, REST }

/** A player's upcoming-week baseline and team: reused, with each remaining week's opponent, for rest of season. */
private class Prepared(val player: PlayerInfo, val team: String, val baseline: Map<String, Double>, val passRate: Double)

/**
 * The walk-forward loop over every regular-season week of the built seasons,
 * oldest first, each projected only from the games before it. Past weeks
 * keep their final projection, for the accuracy backtest. The upcoming week
 * keeps both stages and the waterfall's factors. Every week from the upcoming
 * one on is summed into rest of season, using what's known as of the upcoming
 * week and each week's own opponent and line.
 */
internal class Projector(
    private val inputs: ForecastInputs,
    /** Seasons copied from the previous database: their weeks aren't recomputed. */
    private val copied: Set<Int>,
    private val sink: ProjectionSink,
    private val onWeek: (season: Int, week: Int) -> Unit,
) {
    private val model = BaselineModel(inputs.teamGames, inputs.expectedThrough)
    private val teamHistory: Map<String, List<TeamGame>> =
        inputs.teamGames.values.groupBy { it.team }.mapValues { (_, games) -> games.sortedBy { it.order } }
    private val gameOf: Map<Triple<String, Int, Int>, Game> = buildMap {
        for (g in inputs.games) {
            put(Triple(g.home, g.season, g.week), g)
            put(Triple(g.away, g.season, g.week), g)
        }
    }
    private val teamsIn: Map<Int, Set<String>> =
        inputs.games.groupBy { it.season }.mapValues { (_, games) -> games.flatMap { listOf(it.home, it.away) }.toSet() }
    private val chronological: List<PlayerGame> = inputs.history.values.flatten().sortedBy { it.order }
    private val totals = LeagueTotals()
    private var added = 0

    fun run(): ProjectionOutcome {
        val regular = inputs.games.filter { it.regular }
        if (regular.isEmpty()) return ProjectionOutcome("no schedule", emptyMap(), 0)
        val latest = regular.maxOf { it.season }
        val upcomingWeek = regular.filter { it.season == latest && !it.played }.minOfOrNull { it.week }
        val weeks = regular.map { it.season to it.week }.distinct().sortedWith(compareBy({ it.first }, { it.second }))

        var projected = 0
        var upcoming: Pair<WeekState, List<Prepared>>? = null
        val ros = HashMap<Pair<String, String>, DoubleArray>()
        for ((season, week) in weeks) {
            val kind = when {
                upcomingWeek == null || season < latest || week < upcomingWeek -> WeekKind.PAST
                week == upcomingWeek -> WeekKind.UPCOMING
                else -> WeekKind.REST
            }
            if (kind == WeekKind.REST) {
                val (state, prepared) = upcoming ?: continue
                onWeek(season, week)
                for (p in prepared) addRest(state, p, season, week, ros)
                continue
            }
            advanceTo(order(season, week))
            if (added == 0 || (kind == WeekKind.PAST && season in copied)) continue
            onWeek(season, week)
            projected++
            val state = WeekState(season, week)
            val prepared = candidates(state.order).mapNotNull { projectPlayer(state, it, kind) }
            if (kind == WeekKind.UPCOMING) {
                upcoming = state to prepared
                for (p in prepared) addRest(state, p, season, week, ros)
            }
        }
        if (upcoming != null && upcomingWeek != null) {
            for ((key, sum) in ros) sink.ros(key.first, latest, upcomingWeek - 1, key.second, sum[0], sum[1])
        }
        val status = if (projected == 0) "no games to project from yet" else FORECAST_OK
        val upcomingMap = if (upcoming != null && upcomingWeek != null) mapOf(latest to upcomingWeek) else emptyMap()
        return ProjectionOutcome(status, upcomingMap, projected)
    }

    /** What every player's projection for one week shares: league rates, the average team, defense ratings. */
    private inner class WeekState(val season: Int, val week: Int) {
        val order = order(season, week)
        val rates: Map<String, Rates> = POSITIONS.mapNotNull { p -> totals.rates(p)?.let { p to it } }.toMap()
        val leagueTeam: TeamVolume
        val matchup: MatchupModel
        val leagueImplied: Double

        init {
            val earlier = inputs.teamGames.values.filter { it.order < order }
            leagueTeam = TeamVolume(
                earlier.map { it.passAttempts }.average(),
                earlier.map { it.targets }.average(),
                earlier.map { it.carries }.average(),
            )
            matchup = MatchupModel.fit(
                earlier.filter { it.season == season }.mapNotNull { tg ->
                    gameOf[Triple(tg.team, tg.season, tg.week)]?.let { g -> MatchupGame(tg, g.opponentOf(tg.team), g.isHome(tg.team)) }
                },
            )
            val totalsPosted = inputs.games.filter { it.season == season && it.regular }.mapNotNull { it.total }
            leagueImplied = if (totalsPosted.isEmpty()) K.LEAGUE_IMPLIED_DEFAULT else totalsPosted.average() / 2
        }
    }

    /** Adds every game before [order] to the league totals. */
    private fun advanceTo(order: Int) {
        while (added < chronological.size && chronological[added].order < order) {
            val g = chronological[added++]
            val position = inputs.players[g.playerId]?.position ?: continue
            val team = inputs.teamGames.getValue(Triple(g.team, g.season, g.week))
            totals.add(position, g, team, g.week <= (inputs.expectedThrough[g.season] ?: 0))
        }
    }

    /** Players with a game this season or last, before this week. */
    private fun candidates(order: Int): List<PlayerInfo> {
        val season = order / 100
        return inputs.players.values
            .filter { p -> inputs.history[p.playerId]?.any { it.order < order && it.season >= season - 1 } == true }
            .sortedBy { it.playerId }
    }

    private fun projectPlayer(state: WeekState, player: PlayerInfo, kind: WeekKind): Prepared? {
        val rates = state.rates[player.position] ?: return null
        val all = inputs.history[player.playerId].orEmpty()
        val before = all.takeWhile { it.order < state.order }
        val team = teamFor(player, all, before, state.season, state.week, kind) ?: return null
        val game = gameOf[Triple(team, state.season, state.week)] ?: return null // a bye
        val ctx = PlayerContext(player.position, state.season, state.week, before, regimeBreak(player, team, before, state.season, state.week))
        val volume = teamVolume(teamHistory[team].orEmpty().takeWhile { it.order < state.order }, state.leagueTeam)
        val prepared = Prepared(player, team, model.project(ctx, rates, volume), volume.passRate)
        val (afterMatchup, final) = finalFor(state, prepared, game)
        val cv = K.EMPIRICAL_CV.getValue(player.position)
        when (kind) {
            WeekKind.PAST -> if (referencePoints(final) >= K.PAST_WEEK_MIN_POINTS) {
                emit(player.playerId, state.season, state.week, "final", final, cv)
            }
            WeekKind.UPCOMING -> if (referencePoints(final) >= K.UPCOMING_MIN_POINTS) {
                emit(player.playerId, state.season, state.week, "baseline", prepared.baseline, cv)
                emit(player.playerId, state.season, state.week, "final", final, cv)
                emitFactors(state, prepared, game, afterMatchup, final)
            }
            WeekKind.REST -> Unit
        }
        return prepared
    }

    /**
     * The team a player is projected with: the one he played for that week if
     * he did; for the upcoming week and after, nflverse's current team if it
     * plays this season; otherwise his most recent team.
     */
    private fun teamFor(p: PlayerInfo, all: List<PlayerGame>, before: List<PlayerGame>, season: Int, week: Int, kind: WeekKind): String? {
        all.firstOrNull { it.season == season && it.week == week }?.let { return it.team }
        val current = p.team
        if (kind != WeekKind.PAST && current != null && current in teamsIn[season].orEmpty()) return current
        return before.lastOrNull()?.team
    }

    /** Last season stops counting after a move to a new team, a new head coach, or (pass catchers) a new starting QB. */
    private fun regimeBreak(p: PlayerInfo, team: String, before: List<PlayerGame>, season: Int, week: Int): Boolean {
        val prior = before.filter { it.season == season - 1 }
        if (prior.isEmpty()) return false
        val lastTeam = prior.last().team
        if (lastTeam != team) return true
        val coachNow = gameOf[Triple(team, season, week)]?.coachOf(team)
        val coachThen = gameOf[Triple(lastTeam, season - 1, prior.last().week)]?.coachOf(lastTeam)
        if (coachNow != null && coachThen != null && coachNow != coachThen) return true
        if (p.position == "QB") return false
        val qbNow = startingQb(team, season, week)
        val qbThen = prior.mapNotNull { gameOf[Triple(lastTeam, it.season, it.week)]?.qbOf(lastTeam) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return qbNow != null && qbThen != null && qbNow != qbThen
    }

    /** This week's listed starter, else the team's most recent one this season. */
    private fun startingQb(team: String, season: Int, week: Int): String? =
        gameOf[Triple(team, season, week)]?.qbOf(team)
            ?: inputs.games.lastOrNull { it.season == season && it.week < week && it.involves(team) && it.qbOf(team) != null }?.qbOf(team)

    /** Matchup, then game script: (after matchup, final). */
    private fun finalFor(state: WeekState, p: Prepared, game: Game): Pair<Map<String, Double>, Map<String, Double>> {
        val opponent = game.opponentOf(p.team)
        val home = game.isHome(p.team)
        val afterMatchup = adjust(p.baseline) { side, type -> state.matchup.multiplier(side, type, opponent, home) }
        val script = gameScript(game, p.team, state.leagueImplied, p.passRate) ?: return afterMatchup to afterMatchup
        return afterMatchup to adjust(afterMatchup) { side, type -> script.multiplier(side, type) }
    }

    private fun emitFactors(state: WeekState, p: Prepared, game: Game, afterMatchup: Map<String, Double>, final: Map<String, Double>) {
        val baselinePoints = referencePoints(p.baseline)
        val matchupPoints = referencePoints(afterMatchup)
        val id = p.player.playerId
        sink.factor(
            id, state.season, state.week, "matchup", logRatio(matchupPoints, baselinePoints),
            state.matchup.note(p.player.position, game.opponentOf(p.team)),
        )
        gameScript(game, p.team, state.leagueImplied, p.passRate)?.let { script ->
            sink.factor(id, state.season, state.week, "game_script", logRatio(referencePoints(final), matchupPoints), script.note)
        }
    }

    private fun addRest(state: WeekState, p: Prepared, season: Int, week: Int, ros: MutableMap<Pair<String, String>, DoubleArray>) {
        val game = gameOf[Triple(p.team, season, week)] ?: return // a bye
        val final = finalFor(state, p, game).second
        if (referencePoints(final) < K.UPCOMING_MIN_POINTS) return
        val cv = K.EMPIRICAL_CV.getValue(p.player.position)
        for ((metric, mean) in final) {
            if (mean <= 0.0) continue
            val sum = ros.getOrPut(p.player.playerId to metric) { DoubleArray(2) }
            sum[0] += mean
            sum[1] += varianceFor(mean, cv)
        }
    }

    private fun emit(playerId: String, season: Int, week: Int, stage: String, components: Map<String, Double>, cv: Double) {
        for ((metric, mean) in components) {
            check(mean.isFinite()) { "$playerId's $season week $week $metric projection is $mean" }
            if (mean > 0.0) sink.projection(playerId, season, week, metric, stage, mean, varianceFor(mean, cv))
        }
    }

    private fun logRatio(after: Double, before: Double): Double = if (after > 0.0 && before > 0.0) ln(after / before) else 0.0
}
