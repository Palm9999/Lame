package dev.gridiron.core.ingest.pbp

/** Adds the rate and composite columns `transform.weekly_player_stats` derives after its joins. */
internal fun PlayerWeek.derive() {
    val v = values
    // Guarded division: a zero or missing denominator yields null, not an error or a zero.
    fun ratio(num: String, den: String): Double? {
        val d = v[den] ?: return null
        val n = v[num] ?: return null
        return if (d > 0) n / d else null
    }

    v["g"] = 1.0
    val targetShare = ratio("targets", "team_targets")
    val airYardsShare = ratio("air_yards", "team_air_yards")
    v["target_share"] = targetShare
    v["air_yards_share"] = airYardsShare
    // carries_eff (kneels excluded), so the numerator matches team_carries.
    v["carry_share"] = ratio("carries_eff", "team_carries")
    v["adot"] = ratio("air_yards", "targets")
    v["racr"] = ratio("receiving_yards", "air_yards")
    v["catch_rate"] = ratio("receptions", "targets")
    v["rush_success_rate"] = ratio("rush_successes", "carries_eff")
    v["rush_epa_per_carry"] = ratio("rush_epa", "carries_eff")
    v["epa_per_dropback"] = ratio("pass_epa", "dropbacks")
    v["weighted_opportunities"] = (v["carries_eff"] ?: 0.0) + 2.6 * (v["targets"] ?: 0.0)
    v["total_epa"] = (v["rec_epa"] ?: 0.0) + (v["rush_epa"] ?: 0.0)
    // WOPR's coefficients assume shares in [0, 1]; clamp for this composite only.
    v["wopr"] = 1.5 * (targetShare ?: 0.0).coerceIn(0.0, 1.0) + 0.7 * (airYardsShare ?: 0.0).coerceIn(0.0, 1.0)
}

/** Play-by-play to weekly player stats with every derived column. */
internal fun weeklyPlayerStats(plays: Iterable<Play>): List<PlayerWeek> {
    val aggregator = PlayerWeekAggregator()
    plays.forEach(aggregator::add)
    return aggregator.rows().onEach { it.derive() }
}
