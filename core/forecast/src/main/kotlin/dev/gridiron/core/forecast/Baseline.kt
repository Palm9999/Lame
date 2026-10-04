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
/**
 * Layer 2b: an RB, WR or TE's [projected] stats moved [K.SEASON_FORM_WEIGHT] of the way toward his per-game
 * averages over this season's games so far. A QB, or anyone without a game yet this season, is unchanged.
 */
internal fun withSeasonForm(projected: Map<String, Double>, ctx: PlayerContext): Map<String, Double> {
    if (ctx.position == "QB") return projected
    val games = ctx.history.filter { it.season == ctx.season }
    if (games.isEmpty()) return projected
    val w = K.SEASON_FORM_WEIGHT
    return projected.mapValues { (metric, mean) -> (1 - w) * mean + w * games.sumOf { it[metric] } / games.size }
}

internal class PlayerContext(
    val position: String,
    val season: Int,
    val week: Int,
    /** The player's games before this week, oldest first. */
    val history: List<PlayerGame>,
    /** Last season's share isn't this season's target: a new team, a new head coach, or (pass catchers) a new starting QB. */
    val regimeBreak: Boolean,
)

/** A player's expected shares of his team's volume. [pass] is nonzero only for the expected starting QB, [target] only for non-QBs. */
internal data class Shares(val pass: Double, val target: Double, val carry: Double)

/**
 * Scales one team's shares so its target shares sum to one and its carry
 * shares sum to one: the players projected for a team split exactly its
 * volume. Pass shares are left alone, because one starter takes them.
 */
internal fun normalizeShares(shares: Map<String, Shares>): Map<String, Shares> {
    val targets = shares.values.sumOf { it.target }
    val carries = shares.values.sumOf { it.carry }
    return shares.mapValues { (_, s) ->
        s.copy(
            target = if (targets > 0.0) s.target / targets else 0.0,
            carry = if (carries > 0.0) s.carry / carries else 0.0,
        )
    }
}

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
    /** A lone player's projection, as a starter, with no team normalization. */
    fun project(ctx: PlayerContext, rates: Rates, volume: TeamVolume): Map<String, Double> =
        project(ctx, rates, volume, shares(ctx, rates, starter = true))

    /** Layer 2's raw shares, before [normalizeShares]. [starter]: whether this QB is his team's expected starter. */
    fun shares(ctx: PlayerContext, rates: Rates, starter: Boolean): Shares {
        val carry = share(ctx, rates.carryShare * K.NEWCOMER_SHARE_FACTOR, { it["carries"] }, { it.carries })
        if (ctx.position != "QB") {
            val target = share(ctx, rates.targetShare * K.NEWCOMER_SHARE_FACTOR, { it["targets"] }, { it.targets })
            return Shares(pass = 0.0, target = target, carry = carry)
        }
        // A starter is shrunk toward a starter's share, never toward his own backup history.
        val pass = if (starter) share(ctx, K.STARTER_PASS_SHARE, { it["attempts"] }, { it.passAttempts }, usePrior = false) else 0.0
        return Shares(pass = pass, target = 0.0, carry = carry)
    }

    fun project(ctx: PlayerContext, rates: Rates, volume: TeamVolume, shares: Shares): Map<String, Double> {
        val h = ctx.history
        val out = LinkedHashMap<String, Double>()

        val carries = volume.carries * shares.carry
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
            attempts = volume.passAttempts * shares.pass
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
            val targets = volume.targets * shares.target
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
     * Layer 2: this season's recency-weighted share, shrunk (k = 5 games)
     * toward the player's own last-season share, or toward [fallback] when
     * he has none, his regime broke, or [usePrior] is false.
     */
    internal fun share(
        ctx: PlayerContext,
        fallback: Double,
        part: (PlayerGame) -> Double,
        whole: (TeamGame) -> Double,
        usePrior: Boolean = true,
    ): Double {
        fun series(games: List<PlayerGame>): List<Double> = games.mapNotNull { g ->
            val team = whole(teamGames.getValue(Triple(g.team, g.season, g.week)))
            if (team > 0.0) part(g) / team else null
        }
        val current = series(ctx.history.filter { it.season == ctx.season })
        val prior = if (!usePrior || ctx.regimeBreak) null else ewma(series(ctx.history.filter { it.season == ctx.season - 1 }), K.SHARE_HALF_LIFE)
        return shrink(ewma(current, K.SHARE_HALF_LIFE), current.size.toDouble(), prior ?: fallback, K.SHARE_K_GAMES)
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
