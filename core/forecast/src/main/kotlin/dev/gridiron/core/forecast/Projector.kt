package dev.gridiron.core.forecast

import kotlin.math.ln

internal class ProjectionOutcome(val status: String, val upcoming: Map<Int, Int>, val weeks: Int, val props: PropsOutcome?)

/** Where a week sits relative to the upcoming one: projected and kept, projected with factors, or rest of season only. */
internal enum class WeekKind { PAST, UPCOMING, REST }

/**
 * A player's upcoming-week baseline and team: reused, with each remaining
 * week's opponent, for rest of season. [upcoming] is the upcoming week's
 * final projection with props blended in, when he has one.
 */
private class Prepared(
    val player: PlayerInfo,
    val team: String,
    val baseline: Map<String, Double>,
    val passRate: Double,
    val upcoming: Map<String, Double>? = null,
)

/**
 * The walk-forward loop over every regular-season week of the built seasons,
 * oldest first, each projected only from the games before it. Kickers and
 * D/STs are projected alongside by [UnitProjector]. Past weeks
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
    /** Props for the upcoming week; null when the user has none. */
    private val props: PropsSnapshot?,
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
    private val units = UnitProjector(inputs, gameOf, sink)
    private val teamsIn: Map<Int, Set<String>> =
        inputs.games.groupBy { it.season }.mapValues { (_, games) -> games.flatMap { listOf(it.home, it.away) }.toSet() }
    private val chronological: List<PlayerGame> = inputs.history.values.flatten().sortedBy { it.order }
    private val totals = LeagueTotals()
    private var added = 0
    private var blended = 0

    /**
     * Names in [props] that no projected player matched. Until the upcoming
     * week is matched (or when there is none), that's every name, one per game.
     */
    private var unmatched = props?.events?.sumOf { e -> e.quotes.map { normalizeName(it.player) }.distinct().size } ?: 0

    fun run(): ProjectionOutcome {
        val regular = inputs.games.filter { it.regular }
        if (regular.isEmpty()) return ProjectionOutcome("no schedule", emptyMap(), 0, props?.let { PropsOutcome(0, unmatched) })
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
                units.rest(season, week, ros)
                continue
            }
            advanceTo(order(season, week))
            if (added == 0 || (kind == WeekKind.PAST && season in copied)) continue
            onWeek(season, week)
            projected++
            val state = WeekState(season, week)
            val prepared = prepareWeek(state, kind)
            units.week(season, week, kind, ros)
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
        return ProjectionOutcome(status, upcomingMap, projected, props?.let { PropsOutcome(blended, unmatched) })
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
            leagueImplied = averageImplied(inputs.games, season)
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

    /** A player placed on a team for one week, before his team's shares are worked out. */
    private class Draft(val player: PlayerInfo, val team: String, val game: Game?, val ctx: PlayerContext, val rates: Rates)

    /** One team's week: the QB who passes, who is projected, and their shares. */
    private class Roster(val starter: String?, val kept: List<Draft>, val shares: Map<String, Shares>)

    /**
     * One week's projections, a team at a time: the expected starting QB and
     * the active players, with the team's target and carry shares scaled to
     * sum to one (spec amendment to layer 2). Players nflverse lists Out or
     * Doubtful that week are left out first, so their volume goes to the rest.
     * The upcoming week's players are matched to [props] first.
     */
    private fun prepareWeek(state: WeekState, kind: WeekKind): List<Prepared> {
        val drafts = candidates(state.order).mapNotNull { draft(state, it, kind) }
        val market = if (kind == WeekKind.UPCOMING && props != null) {
            MarketMatch(
                props,
                inputs.games.filter { it.season == state.season && it.week == state.week }.map { it.home to it.away },
                drafts.map { PropCandidate(it.player.playerId, it.player.name, it.team) },
            ).also { unmatched = it.unmatched }
        } else {
            null
        }
        return drafts.groupBy { it.team }.flatMap { (team, onTeam) ->
            val volume = teamVolume(teamHistory[team].orEmpty().takeWhile { it.order < state.order }, state.leagueTeam)
            val roster = roster(team, onTeam, state, kind, respectAbsent = true)
            val prepared = roster.kept.map { d -> finish(state, d, roster.shares.getValue(d.player.playerId), volume, kind, market) }
            if (kind != WeekKind.UPCOMING) return@flatMap prepared
            // Rest of season starts from this list. An injury this week says nothing about later weeks, so it
            // gets the healthy roster's baseline; this week's own contribution is what was just projected.
            val healthy = roster(team, onTeam, state, kind, respectAbsent = false)
            if (healthy.starter == roster.starter && healthy.kept.size == roster.kept.size) return@flatMap prepared
            val projected = prepared.associateBy { it.player.playerId }
            healthy.kept.map { d ->
                val id = d.player.playerId
                Prepared(
                    d.player, d.team, model.project(d.ctx, d.rates, volume, healthy.shares.getValue(id)), volume.passRate,
                    upcoming = projected[id]?.upcoming ?: emptyMap(),
                )
            }
        }
    }

    private fun roster(team: String, onTeam: List<Draft>, state: WeekState, kind: WeekKind, respectAbsent: Boolean): Roster {
        val available = if (respectAbsent) onTeam.filterNot { isAbsent(it, state) } else onTeam
        val starter = expectedStarter(team, available, state, kind)
        val kept = available.filter { d ->
            if (d.player.position == "QB") d.player.playerId == starter else isActive(d, team, state, kind)
        }
        val shares = normalizeShares(
            kept.associate { d -> d.player.playerId to model.shares(d.ctx, d.rates, starter = d.player.playerId == starter) },
        )
        return Roster(starter, kept, shares)
    }

    /** Whether nflverse lists him Out or Doubtful this week. */
    private fun isAbsent(d: Draft, state: WeekState): Boolean = Triple(d.player.playerId, state.season, state.week) in inputs.absent

    private fun draft(state: WeekState, player: PlayerInfo, kind: WeekKind): Draft? {
        val rates = state.rates[player.position] ?: return null
        val all = inputs.history[player.playerId].orEmpty()
        val before = all.takeWhile { it.order < state.order }
        val team = teamFor(player, all, before, state.season, state.week, kind) ?: return null
        val game = gameOf[Triple(team, state.season, state.week)]
        // A past bye has nothing to project. An upcoming bye has no weekly rows, but its later games still make rest of season.
        if (game == null && kind != WeekKind.UPCOMING) return null
        val ctx = PlayerContext(player.position, state.season, state.week, before, regimeBreak(player, team, before, state.season, state.week))
        return Draft(player, team, game, ctx, rates)
    }

    /**
     * Whether a non-QB is on the field for [team] as of this week: he played
     * for it in one of its last [K.ACTIVE_WINDOW] games, or, from the upcoming
     * week on, nflverse lists him on [team] and he hasn't played for it yet (a
     * signing or trade).
     */
    private fun isActive(d: Draft, team: String, state: WeekState, kind: WeekKind): Boolean {
        val recent = teamHistory[team].orEmpty().filter { it.order < state.order }.takeLast(K.ACTIVE_WINDOW).map { it.order }.toSet()
        if (d.ctx.history.any { it.team == team && it.order in recent }) return true
        return kind != WeekKind.PAST && d.player.team == team && d.ctx.history.lastOrNull()?.team != team
    }

    /**
     * The QB who gets [team]'s passing this week. In order: the starter
     * nflverse lists for the game; else the most recent listed starter who is
     * still with the team; else the team's QB with the most attempts in its
     * latest game. Null when the team has no QB candidate.
     */
    private fun expectedStarter(team: String, onTeam: List<Draft>, state: WeekState, kind: WeekKind): String? {
        val qbs = onTeam.filter { it.player.position == "QB" }
        if (qbs.isEmpty()) return null
        val ids = qbs.map { it.player.playerId }.toSet()
        gameOf[Triple(team, state.season, state.week)]?.qbOf(team)?.takeIf { it in ids }?.let { return it }
        inputs.games
            .filter { it.involves(team) && order(it.season, it.week) < state.order }
            .mapNotNull { it.qbOf(team) }
            .lastOrNull { it in ids && (kind == WeekKind.PAST || inputs.players[it]?.team == team) }
            ?.let { return it }
        val latest = teamHistory[team].orEmpty().lastOrNull { it.order < state.order }?.order
        return qbs.maxByOrNull { d -> d.ctx.history.lastOrNull { it.team == team && it.order == latest }?.get("attempts") ?: 0.0 }?.player?.playerId
    }

    private fun finish(state: WeekState, d: Draft, shares: Shares, volume: TeamVolume, kind: WeekKind, market: MarketMatch?): Prepared {
        val prepared = Prepared(d.player, d.team, model.project(d.ctx, d.rates, volume, shares), volume.passRate)
        val game = d.game ?: return prepared
        val (afterMatchup, final) = finalFor(state, prepared, game)
        val cv = K.EMPIRICAL_CV.getValue(d.player.position)
        when (kind) {
            WeekKind.PAST -> if (referencePoints(final) >= K.PAST_WEEK_MIN_POINTS) {
                emit(d.player.playerId, state.season, state.week, "final", final, cv)
            }
            WeekKind.UPCOMING -> {
                val withProps = market?.quotes(d.player.playerId)?.let { blend(final, it, d.player.position) }
                val shown = withProps?.components ?: final
                if (referencePoints(shown) >= K.UPCOMING_MIN_POINTS) {
                    emit(d.player.playerId, state.season, state.week, "baseline", prepared.baseline, cv)
                    emit(d.player.playerId, state.season, state.week, "final", shown, cv)
                    emitFactors(state, prepared, game, afterMatchup, final, withProps)
                    if (withProps != null) blended++
                }
                return Prepared(d.player, d.team, prepared.baseline, prepared.passRate, upcoming = shown)
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

    private fun emitFactors(
        state: WeekState,
        p: Prepared,
        game: Game,
        afterMatchup: Map<String, Double>,
        final: Map<String, Double>,
        withProps: Blended?,
    ) {
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
        withProps?.let {
            sink.factor(id, state.season, state.week, "market", logRatio(referencePoints(it.components), referencePoints(final)), it.note)
        }
    }

    private fun addRest(state: WeekState, p: Prepared, season: Int, week: Int, ros: MutableMap<Pair<String, String>, DoubleArray>) {
        val game = gameOf[Triple(p.team, season, week)] ?: return // a bye
        // The upcoming week itself uses what was stored for it, props included.
        val final = p.upcoming?.takeIf { week == state.week && season == state.season } ?: finalFor(state, p, game).second
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
