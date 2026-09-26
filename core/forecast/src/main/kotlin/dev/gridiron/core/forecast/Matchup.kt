package dev.gridiron.core.forecast

/** What a defense is rated on, from the offense's side of each team-game. */
internal enum class Outcome(val label: String) {
    PASS_YARDS("pass yards per attempt"),
    RUSH_YARDS("rush yards per carry"),
    PASS_TD("pass TDs per attempt"),
    RUSH_TD("rush TDs per carry"),
    PLAYS("plays"),
    ;

    /** This outcome for one team-game, or null without a denominator. */
    fun of(g: TeamGame): Double? = when (this) {
        PASS_YARDS -> if (g.passAttempts > 0.0) g.passYards / g.passAttempts else null
        RUSH_YARDS -> if (g.carries > 0.0) g.rushYards / g.carries else null
        PASS_TD -> if (g.passAttempts > 0.0) g.passTds / g.passAttempts else null
        RUSH_TD -> if (g.carries > 0.0) g.rushTds / g.carries else null
        PLAYS -> g.passAttempts + g.carries
    }

    val cap: Double
        get() = when (this) {
            PASS_YARDS, RUSH_YARDS -> K.CAP_EFFICIENCY
            PASS_TD, RUSH_TD -> K.CAP_TD
            PLAYS -> K.CAP_VOLUME
        }
}

/** One team-game and whom it was against. */
internal class MatchupGame(val game: TeamGame, val opponent: String, val home: Boolean)

/**
 * Layer 5: opponent-adjusted defense ratings, one ridge fit per [Outcome] on
 * the season's earlier games, shrunk again by games rated so weeks 2-3 sit
 * near league average, and turned into capped multipliers. It adjusts only
 * for the opponent: the player's own offense is already in his rates.
 */
internal class MatchupModel private constructor(private val fits: Map<Outcome, RidgeFit>) {

    fun multiplier(outcome: Outcome, opponent: String, home: Boolean): Double {
        val fit = fits[outcome] ?: return 1.0
        if (fit.mean <= 0.0) return 1.0
        val rating = fit.defense[opponent] ?: return 1.0
        val n = (fit.defenseGames[opponent] ?: 0).toDouble()
        val effect = rating * n / (n + K.MATCHUP_K_GAMES) + fit.home * (if (home) 0.5 else -0.5)
        return ((fit.mean + effect) / fit.mean).capAround(outcome.cap)
    }

    /** A stat's multiplier: plays for every stat, times the efficiency rating for yards or the TD rating for TDs. */
    fun multiplier(side: Side, type: StatType, opponent: String, home: Boolean): Double {
        val volume = multiplier(Outcome.PLAYS, opponent, home)
        val extra = when (type) {
            StatType.YARDS -> multiplier(if (side == Side.RUSH) Outcome.RUSH_YARDS else Outcome.PASS_YARDS, opponent, home)
            StatType.TD -> multiplier(if (side == Side.RUSH) Outcome.RUSH_TD else Outcome.PASS_TD, opponent, home)
            StatType.VOLUME, StatType.COUNT -> 1.0
        }
        return volume * extra
    }

    /** "vs DAL: 3rd-most pass yards per attempt allowed", from the rating that matters most to [position]. */
    fun note(position: String, opponent: String): String {
        val outcome = if (position == "RB") Outcome.RUSH_YARDS else Outcome.PASS_YARDS
        val fit = fits[outcome] ?: return "vs $opponent: too early to rate defenses"
        val ranked = fit.defense.entries.sortedByDescending { it.value }.map { it.key }
        val rank = ranked.indexOf(opponent) + 1
        if (rank == 0) return "vs $opponent: not rated yet"
        return if (rank <= (ranked.size + 1) / 2) {
            "vs $opponent: ${ordinal(rank)}-most ${outcome.label} allowed"
        } else {
            "vs $opponent: ${ordinal(ranked.size - rank + 1)}-fewest ${outcome.label} allowed"
        }
    }

    companion object {
        fun fit(games: List<MatchupGame>): MatchupModel {
            val fits = Outcome.entries.mapNotNull { outcome ->
                val rows = games.mapNotNull { m -> outcome.of(m.game)?.let { RidgeRow(m.game.team, m.opponent, m.home, it) } }
                if (rows.size < K.MIN_MATCHUP_ROWS) null else outcome to fitRidge(rows)
            }.toMap()
            return MatchupModel(fits)
        }
    }
}
