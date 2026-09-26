package dev.gridiron.core.forecast

/** Stats summed straight across games at a position. */
private val SUMMED = listOf(
    "targets", "receptions", "receiving_yards", "receiving_tds", "receiving_tds_40", "receiving_tds_50",
    "carries", "rushing_yards", "rushing_tds", "rushing_tds_40", "rushing_tds_50",
    "attempts", "completions", "passing_yards", "passing_tds", "passing_tds_40", "passing_tds_50",
    "interceptions", "sacks_taken", "passing_first_downs", "rushing_first_downs", "receiving_first_downs",
    "passing_2pt", "rushing_2pt", "receiving_2pt", "fumbles_lost",
)

/**
 * Running league sums by position, over every game before the week being
 * projected: the shrinkage baselines and the rare-event rates. The projector
 * adds games in chronological order and takes a [rates] snapshot per week.
 */
internal class LeagueTotals {
    private val byPosition = HashMap<String, HashMap<String, Double>>()

    /** [expectedCovered]: whether ffopportunity had processed this week, so its expected TDs are real zeros, not missing. */
    fun add(position: String, g: PlayerGame, team: TeamGame, expectedCovered: Boolean) {
        val sums = byPosition.getOrPut(position) { HashMap() }
        fun plus(key: String, value: Double) {
            sums[key] = (sums[key] ?: 0.0) + value
        }
        plus("team_targets", team.targets)
        plus("team_carries", team.carries)
        plus("team_attempts", team.passAttempts)
        for (metric in SUMMED) plus(metric, g[metric])
        plus("touches", g["carries"] + g["receptions"] + g["attempts"])
        if (expectedCovered) {
            plus("x_rec_td", g["x_receiving_tds"])
            plus("x_rec_td_targets", g["targets"])
            plus("x_rush_td", g["x_rushing_tds"])
            plus("x_rush_td_carries", g["carries"])
            plus("x_pass_td", g["x_passing_tds"])
            plus("x_pass_td_attempts", g["attempts"])
        }
    }

    fun rates(position: String): Rates? = byPosition[position]?.let { Rates(HashMap(it)) }
}

/** Positional baselines and rare-event rates from a snapshot of [LeagueTotals]. Zero where there's no denominator. */
internal class Rates(private val sums: Map<String, Double>) {
    private fun ratio(numerator: String, denominator: String): Double {
        val d = sums[denominator] ?: 0.0
        return if (d > 0.0) (sums[numerator] ?: 0.0) / d else 0.0
    }

    /** Expected TDs per opportunity from covered weeks; actual TDs when ffopportunity covered none. */
    private fun expectedOr(x: String, xDenominator: String, actual: String, denominator: String): Double =
        if ((sums[xDenominator] ?: 0.0) > 0.0) ratio(x, xDenominator) else ratio(actual, denominator)

    val targetShare: Double = ratio("targets", "team_targets")
    val carryShare: Double = ratio("carries", "team_carries")
    val passShare: Double = ratio("attempts", "team_attempts")
    val catchRate: Double = ratio("receptions", "targets")
    val yardsPerTarget: Double = ratio("receiving_yards", "targets")
    val yardsPerCarry: Double = ratio("rushing_yards", "carries")
    val completionRate: Double = ratio("completions", "attempts")
    val yardsPerAttempt: Double = ratio("passing_yards", "attempts")
    val interceptionRate: Double = ratio("interceptions", "attempts")
    val sackRate: Double = ratio("sacks_taken", "attempts")
    val xReceivingTdPerTarget: Double = expectedOr("x_rec_td", "x_rec_td_targets", "receiving_tds", "targets")
    val xRushingTdPerCarry: Double = expectedOr("x_rush_td", "x_rush_td_carries", "rushing_tds", "carries")
    val xPassingTdPerAttempt: Double = expectedOr("x_pass_td", "x_pass_td_attempts", "passing_tds", "attempts")
    val recFirstDownsPerReception: Double = ratio("receiving_first_downs", "receptions")
    val rushFirstDownsPerCarry: Double = ratio("rushing_first_downs", "carries")
    val passFirstDownsPerCompletion: Double = ratio("passing_first_downs", "completions")
    val rec2ptPerTarget: Double = ratio("receiving_2pt", "targets")
    val rush2ptPerCarry: Double = ratio("rushing_2pt", "carries")
    val pass2ptPerAttempt: Double = ratio("passing_2pt", "attempts")
    val fumblesPerTouch: Double = ratio("fumbles_lost", "touches")

    /** Share of [kind] ("passing", "rushing", "receiving") TDs that went for at least [yards] (40 or 50). */
    fun longTdShare(kind: String, yards: Int): Double = ratio("${kind}_tds_$yards", "${kind}_tds")
}
