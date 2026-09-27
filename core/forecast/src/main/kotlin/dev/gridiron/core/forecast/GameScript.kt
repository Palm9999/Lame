package dev.gridiron.core.forecast

import java.util.Locale
import kotlin.math.pow

/** Layer 6 for one team's game: how its line moves each kind of stat. */
internal class GameScript(
    val implied: Double,
    val leagueImplied: Double,
    /** Implied points over the league's average, capped to [K.IMPLIED_RATIO_MIN]..[K.IMPLIED_RATIO_MAX]. */
    private val ratio: Double,
    val passAdjust: Double,
    val rushAdjust: Double,
) {
    fun multiplier(side: Side, type: StatType): Double {
        val sideAdjust = when (side) {
            Side.PASS -> passAdjust
            Side.RUSH -> rushAdjust
            Side.TOUCH -> 1.0
        }
        val elasticity = when (type) {
            StatType.TD -> K.ELASTICITY_TD
            StatType.YARDS -> K.ELASTICITY_YARDS
            StatType.VOLUME, StatType.COUNT -> K.ELASTICITY_ATTEMPTS
        }
        return ratio.pow(elasticity) * sideAdjust
    }

    /** "Implied 27.5 pts (+4.3)": the team's implied points and how far they are from the league's average. */
    val note: String get() = String.format(Locale.US, "Implied %.1f pts (%+.1f)", implied, implied - leagueImplied)
}

/**
 * [team]'s game script in [game], or null while the line isn't posted.
 * [passRate] is the team's usual share of pass plays; underdogs pass more.
 */
internal fun gameScript(game: Game, team: String, leagueImplied: Double, passRate: Double): GameScript? {
    val implied = game.impliedPoints(team) ?: return null
    val favoredBy = game.favoredBy(team) ?: return null
    val ratio = (implied / leagueImplied).coerceIn(K.IMPLIED_RATIO_MIN, K.IMPLIED_RATIO_MAX)
    val usual = passRate.coerceIn(K.PASS_RATE_MIN, K.PASS_RATE_MAX)
    val scripted = (usual - K.PASS_RATE_PER_POINT * favoredBy).coerceIn(K.PASS_RATE_MIN, K.PASS_RATE_MAX)
    return GameScript(implied, leagueImplied, ratio, scripted / usual, (1 - scripted) / (1 - usual))
}

/** A team's average implied points in [season]: half its average posted total, or [K.LEAGUE_IMPLIED_DEFAULT] before any. */
internal fun averageImplied(games: List<Game>, season: Int): Double {
    val totals = games.filter { it.season == season && it.regular }.mapNotNull { it.total }
    return if (totals.isEmpty()) K.LEAGUE_IMPLIED_DEFAULT else totals.average() / 2
}
