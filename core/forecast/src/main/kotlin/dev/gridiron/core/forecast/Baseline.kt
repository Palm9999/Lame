package dev.gridiron.core.forecast

/** A team's plays per game, recency-weighted. */
internal data class TeamVolume(val passAttempts: Double, val targets: Double, val carries: Double) {
    /** Share of plays that are passes, before game script. */
    val passRate: Double get() = if (passAttempts + carries > 0.0) passAttempts / (passAttempts + carries) else 0.5
}

/** Layer 1: an EWMA (half-life 4) of the team's earlier games, or [fallback], the league's average team, without any. */
internal fun teamVolume(games: List<TeamGame>, fallback: TeamVolume): TeamVolume {
    if (games.isEmpty()) return fallback
    return TeamVolume(
        passAttempts = ewma(games.map { it.passAttempts }, K.TEAM_HALF_LIFE)!!,
        targets = ewma(games.map { it.targets }, K.TEAM_HALF_LIFE)!!,
        carries = ewma(games.map { it.carries }, K.TEAM_HALF_LIFE)!!,
    )
}

/** What the model knows about one player for one week. */
internal class PlayerContext(
    val position: String,
    val season: Int,
    val week: Int,
    /** The player's games before this week, oldest first. */
    val history: List<PlayerGame>,
    /** Last season shouldn't count: a new team, a new head coach, or (pass catchers) a new starting QB. */
    val regimeBreak: Boolean,
)

/**
 * Layers 1-4 of the spec's model: team volume times the player's share,
 * times shrunk efficiency, with touchdowns from expected TDs and rare events
 * at league rates. Its output is the "baseline" stage, before matchup and
 * game script.
 */
internal class BaselineModel(
    private val teamGames: Map<Triple<String, Int, Int>, TeamGame>,
    private val expectedThrough: Map<Int, Int>,
) {
    fun project(ctx: PlayerContext, rates: Rates, volume: TeamVolume): Map<String, Double> {
        val h = ctx.history
        val out = LinkedHashMap<String, Double>()

        val carries = volume.carries * share(ctx, rates.carryShare, { it["carries"] }, { it.carries })
        val rushTds = carries * tdRate(h, "x_rushing_tds", "carries", rates.xRushingTdPerCarry)
        out["carries"] = carries
        out["rushing_yards"] = carries * efficiency(h, "rushing_yards", "carries", rates.yardsPerCarry)
        out["rushing_tds"] = rushTds
        out["rushing_tds_40"] = rushTds * rates.longTdShare("rushing", 40)
        out["rushing_tds_50"] = rushTds * rates.longTdShare("rushing", 50)
        out["rushing_first_downs"] = carries * rates.rushFirstDownsPerCarry
        out["rushing_2pt"] = carries * rates.rush2ptPerCarry

        var receptions = 0.0
        var attempts = 0.0
        if (ctx.position == "QB") {
            attempts = volume.passAttempts * share(ctx, rates.passShare, { it["attempts"] }, { it.passAttempts })
            val completions = attempts * efficiency(h, "completions", "attempts", rates.completionRate)
            val passTds = attempts * tdRate(h, "x_passing_tds", "attempts", rates.xPassingTdPerAttempt)
            out["attempts"] = attempts
            out["completions"] = completions
            out["passing_yards"] = attempts * efficiency(h, "passing_yards", "attempts", rates.yardsPerAttempt)
            out["passing_tds"] = passTds
            out["passing_tds_40"] = passTds * rates.longTdShare("passing", 40)
            out["passing_tds_50"] = passTds * rates.longTdShare("passing", 50)
            out["interceptions"] = attempts * interceptionRate(h, rates.interceptionRate)
            out["sacks_taken"] = attempts * rates.sackRate
            out["passing_first_downs"] = completions * rates.passFirstDownsPerCompletion
            out["passing_2pt"] = attempts * rates.pass2ptPerAttempt
        } else {
            val targets = volume.targets * share(ctx, rates.targetShare, { it["targets"] }, { it.targets })
            receptions = targets * efficiency(h, "receptions", "targets", rates.catchRate)
            val recTds = targets * tdRate(h, "x_receiving_tds", "targets", rates.xReceivingTdPerTarget)
            out["targets"] = targets
            out["receptions"] = receptions
            out["receiving_yards"] = targets * efficiency(h, "receiving_yards", "targets", rates.yardsPerTarget)
            out["receiving_tds"] = recTds
            out["receiving_tds_40"] = recTds * rates.longTdShare("receiving", 40)
            out["receiving_tds_50"] = recTds * rates.longTdShare("receiving", 50)
            out["receiving_first_downs"] = receptions * rates.recFirstDownsPerReception
            out["receiving_2pt"] = targets * rates.rec2ptPerTarget
        }
        out["fumbles_lost"] = (carries + receptions + attempts) * rates.fumblesPerTouch
        return out
    }

    /**
     * Layer 2: this season's recency-weighted share, shrunk toward the
     * position's, blended with last season's final share early in the season
     * unless the regime broke.
     */
    internal fun share(ctx: PlayerContext, baseline: Double, part: (PlayerGame) -> Double, whole: (TeamGame) -> Double): Double {
        fun series(games: List<PlayerGame>): List<Double> = games.mapNotNull { g ->
            val team = whole(teamGames.getValue(Triple(g.team, g.season, g.week)))
            if (team > 0.0) part(g) / team else null
        }
        val current = series(ctx.history.filter { it.season == ctx.season })
        val shrunk = shrink(ewma(current, K.SHARE_HALF_LIFE), current.size.toDouble(), baseline, K.SHARE_K_GAMES)
        val prior = if (ctx.regimeBreak) null else ewma(series(ctx.history.filter { it.season == ctx.season - 1 }), K.SHARE_HALF_LIFE)
        val w = if (prior == null) 0.0 else carryoverWeight(ctx.week)
        return w * (prior ?: 0.0) + (1 - w) * shrunk
    }

    /** Layer 3: a rate over every earlier game (half-life 10), shrunk toward the position's with k = 15 games. */
    private fun efficiency(games: List<PlayerGame>, numerator: String, denominator: String, baseline: Double): Double {
        val used = games.filter { it[denominator] > 0.0 }
        val observed = ewmaRatio(used.map { it[numerator] }, used.map { it[denominator] }, K.EFFICIENCY_HALF_LIFE)
        return shrink(observed, used.size.toDouble(), baseline, K.EFFICIENCY_K_GAMES)
    }

    /** Interceptions are rarer still: k counts attempts (150), not games. */
    private fun interceptionRate(games: List<PlayerGame>, baseline: Double): Double {
        val used = games.filter { it["attempts"] > 0.0 }
        val observed = ewmaRatio(used.map { it["interceptions"] }, used.map { it["attempts"] }, K.EFFICIENCY_HALF_LIFE)
        return shrink(observed, used.sumOf { it["attempts"] }, baseline, K.INT_K_ATTEMPTS)
    }

    /**
     * Layer 4: expected TDs per opportunity from the weeks ffopportunity has
     * processed (a lagging week's expected TDs are absent, not zero), shrunk
     * hard toward the position's (k = 200 opportunities).
     */
    private fun tdRate(games: List<PlayerGame>, expected: String, denominator: String, baseline: Double): Double {
        val covered = games.filter { it[denominator] > 0.0 && it.week <= (expectedThrough[it.season] ?: 0) }
        val observed = ewmaRatio(covered.map { it[expected] }, covered.map { it[denominator] }, K.EFFICIENCY_HALF_LIFE)
        return shrink(observed, covered.sumOf { it[denominator] }, baseline, K.TD_K_OPPORTUNITIES)
    }
}
