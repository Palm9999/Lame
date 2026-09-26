package dev.gridiron.core.ingest

/**
 * Every metric the app knows about: a line-for-line port of
 * `etl/gridiron_etl/metrics.py`, written verbatim into the `metric` table.
 * Adding a metric is a data change, not a schema migration.
 */
internal data class Metric(
    val id: String,
    val name: String,
    val abbr: String,
    val group: String,
    val definition: String,
    val formula: String? = null,
    val positions: List<String> = ALL_POSITIONS,
    /** A = free from play-by-play, B = free auxiliary feed. */
    val tier: String = "A",
    val predicts: String? = null,
    val stability: Double? = null,
    val higherIsBetter: Boolean = true,
    val decimals: Int = 1,
    val hot: Boolean = false,
    /** Range-aggregation components; never offered as a visible column. */
    val isInternal: Boolean = false,
    /** Computed on the device from other components; never stored as facts. */
    val computed: Boolean = false,
    /** Zero values aren't stored: absent means zero. */
    val sparse: Boolean = false,
    val distFamily: String? = null,
    val zeroInflated: Boolean = false,
)

private val ALL_POSITIONS = listOf("QB", "RB", "WR", "TE")
private val PASS_CATCHERS = listOf("RB", "WR", "TE")
private val RUSHERS = listOf("QB", "RB", "WR")
private val QB_RB = listOf("QB", "RB")
private val QB = listOf("QB")
private val RB = listOf("RB")

private class Component(val id: String, val name: String, val definition: String, val decimals: Int)

private val SCORING_INPUTS = listOf(
    Component("passing_first_downs", "Passing First Downs", "First downs gained by completions, credited to the passer.", 0),
    Component("rushing_first_downs", "Rushing First Downs", "First downs gained on carries.", 0),
    Component("receiving_first_downs", "Receiving First Downs", "First downs gained on receptions.", 0),
    Component("passing_2pt", "Passing 2-pt Conversions", "Successful two-point passes.", 0),
    Component("rushing_2pt", "Rushing 2-pt Conversions", "Successful two-point runs.", 0),
    Component("receiving_2pt", "Receiving 2-pt Conversions", "Successful two-point catches.", 0),
    Component("fumbles_lost", "Fumbles Lost", "Fumbles lost by the ball carrier, including sack fumbles.", 0),
    Component("passing_tds_40", "40+ Yd Passing TDs", "Passing touchdowns of at least 40 yards.", 0),
    Component("passing_tds_50", "50+ Yd Passing TDs", "Passing touchdowns of at least 50 yards.", 0),
    Component("rushing_tds_40", "40+ Yd Rushing TDs", "Rushing touchdowns of at least 40 yards.", 0),
    Component("rushing_tds_50", "50+ Yd Rushing TDs", "Rushing touchdowns of at least 50 yards.", 0),
    Component("receiving_tds_40", "40+ Yd Receiving TDs", "Receiving touchdowns of at least 40 yards.", 0),
    Component("receiving_tds_50", "50+ Yd Receiving TDs", "Receiving touchdowns of at least 50 yards.", 0),
    Component("x_completions", "Expected Completions", "Opportunity-model expected completions.", 2),
    Component("x_receptions", "Expected Receptions", "Opportunity-model expected receptions.", 2),
    Component("x_passing_yards", "Expected Passing Yards", "Opportunity-model expected passing yards.", 2),
    Component("x_rushing_yards", "Expected Rushing Yards", "Opportunity-model expected rushing yards.", 2),
    Component("x_receiving_yards", "Expected Receiving Yards", "Opportunity-model expected receiving yards.", 2),
    Component("x_passing_tds", "Expected Passing TDs", "Opportunity-model expected passing touchdowns.", 2),
    Component("x_rushing_tds", "Expected Rushing TDs", "Opportunity-model expected rushing touchdowns.", 2),
    Component("x_receiving_tds", "Expected Receiving TDs", "Opportunity-model expected receiving touchdowns.", 2),
    Component("x_passing_2pt", "Expected Passing 2-pt", "Opportunity-model expected two-point passes.", 2),
    Component("x_rushing_2pt", "Expected Rushing 2-pt", "Opportunity-model expected two-point runs.", 2),
    Component("x_receiving_2pt", "Expected Receiving 2-pt", "Opportunity-model expected two-point catches.", 2),
    Component("x_passing_first_downs", "Expected Passing First Downs", "Opportunity-model expected passing first downs.", 2),
    Component("x_rushing_first_downs", "Expected Rushing First Downs", "Opportunity-model expected rushing first downs.", 2),
    Component("x_receiving_first_downs", "Expected Receiving First Downs", "Opportunity-model expected receiving first downs.", 2),
    Component("x_interceptions", "Expected Interceptions", "Opportunity-model expected interceptions thrown.", 2),
)

private val RANGE_COMPONENTS = listOf(
    Component("g", "Games", "1 for each week the player recorded a play. Summed " +
        "over a range it gives games played.", 0),
    Component("team_targets", "Team Targets", "Team targets in games this player appeared in.", 0),
    Component("team_air_yards", "Team Air Yards", "Team air yards in games this player appeared in.", 0),
    Component("team_carries", "Team Carries", "Team carries in games this player appeared in.", 0),
    Component("carries_eff", "Efficiency Carries",
        "Carries excluding QB kneels and spikes — the denominator behind " +
            "carry_share, rush_success_rate and rush_epa_per_carry, so those " +
            "rates recompute correctly over a range instead of drifting once " +
            "a kneel enters the box-score carries total. Weighted " +
            "opportunities are built on it too.", 0),
    Component("team_offense_snaps", "Team Offensive Snaps", "Team offensive snaps in games this player appeared in.", 0),
    Component("rush_successes", "Rush Successes", "Carries with positive EPA.", 0),
    Component("rush_epa", "Rush EPA", "Summed EPA on carries.", 3),
    Component("rec_epa", "Receiving EPA", "Summed EPA on targets.", 3),
    Component("pass_epa", "Pass EPA", "Summed EPA on dropbacks.", 3),
    Component("cpoe_sum", "CPOE Sum", "Summed per-attempt CPOE.", 3),
    Component("cpoe_n", "CPOE Attempts", "Attempts with a CPOE value.", 0),
)

internal val METRICS: List<Metric> = listOf(
    // ---------------- Receiving volume ----------------
    Metric("targets", "Targets", "TGT", "volume",
        "Pass attempts thrown in this player's direction, including incompletions.",
        positions = PASS_CATCHERS, predicts = "Receiving production floor",
        stability = 0.70, decimals = 0, hot = true),
    Metric("receptions", "Receptions", "REC", "volume",
        "Completed catches.", positions = PASS_CATCHERS, decimals = 0, hot = true),
    Metric("receiving_yards", "Receiving Yards", "REC YDS", "volume",
        "Total yards gained on receptions.", positions = PASS_CATCHERS, decimals = 0, hot = true),
    Metric("receiving_tds", "Receiving TDs", "REC TD", "volume",
        "Touchdowns scored as a receiver.", positions = PASS_CATCHERS,
        predicts = "Weak — TD rate regresses hard", stability = 0.28, decimals = 0, hot = true),
    Metric("air_yards", "Air Yards", "AY", "volume",
        "Total distance the ball travelled in the air on all targets, caught or not.",
        positions = PASS_CATCHERS, predicts = "Opportunity independent of catch outcome",
        stability = 0.62, decimals = 0, hot = true),
    Metric("target_share", "Target Share", "TGT%", "volume",
        "Share of the team's targets that went to this player.",
        formula = "player_targets / team_targets", positions = PASS_CATCHERS,
        predicts = "Most predictive raw receiving stat", stability = 0.72, decimals = 3, hot = true),
    Metric("air_yards_share", "Air Yards Share", "AY%", "volume",
        "Share of the team's air yards directed at this player. Can fall " +
            "below 0 or above 1 in a single week: about 18% of pass attempts " +
            "carry negative air yards, so a screen-heavy role produces a " +
            "negative share and a teammate can exceed the team total.",
        formula = "player_air_yards / team_air_yards", positions = PASS_CATCHERS,
        stability = 0.65, decimals = 3, hot = true),
    Metric("wopr", "Weighted Opportunity Rating", "WOPR", "volume",
        "Composite of target share and air yards share. The best single " +
            "opportunity number for pass catchers; above 0.70 is elite.",
        formula = "1.5 * target_share + 0.7 * air_yards_share", positions = PASS_CATCHERS,
        predicts = "Receiving fantasy points", stability = 0.71, decimals = 3, hot = true),
    Metric("adot", "Average Depth of Target", "aDOT", "efficiency",
        "Mean air yards per target. A role classifier, not a quality measure — " +
            "it makes catch rate interpretable.",
        formula = "air_yards / targets", positions = PASS_CATCHERS,
        stability = 0.76, decimals = 1, hot = true),
    Metric("racr", "Receiver Air Conversion Ratio", "RACR", "efficiency",
        "Receiving yards produced per air yard thrown. Pairs with aDOT to " +
            "separate deep threats from YAC producers.",
        formula = "receiving_yards / air_yards", positions = PASS_CATCHERS,
        stability = 0.35, decimals = 2),
    Metric("yac", "Yards After Catch", "YAC", "efficiency",
        "Yards gained after the catch.", positions = PASS_CATCHERS, decimals = 0),
    Metric("catch_rate", "Catch Rate", "CTCH%", "efficiency",
        "Receptions per target. Only interpretable alongside aDOT.",
        formula = "receptions / targets", positions = PASS_CATCHERS, decimals = 3),
    Metric("rz_targets", "Red Zone Targets", "RZ TGT", "usage",
        "Targets on snaps starting inside the opponent 20.",
        positions = PASS_CATCHERS, predicts = "Touchdown opportunity", decimals = 0, hot = true),
    Metric("ez_targets", "End Zone Targets", "EZ TGT", "usage",
        "Targets thrown to or beyond the goal line. Worth roughly 3.0 expected " +
            "points versus 1.8 for a target from the 19.",
        formula = "targets where air_yards >= yardline_100",
        positions = PASS_CATCHERS, predicts = "Touchdown opportunity", decimals = 0),

    // ---------------- Rushing ----------------
    Metric("carries", "Carries", "CAR", "volume",
        "Rushing attempts, QB kneels included, matching the box score.",
        positions = RUSHERS, decimals = 0, hot = true),
    Metric("rushing_yards", "Rushing Yards", "RUSH YDS", "volume",
        "Total rushing yards.", positions = RUSHERS, decimals = 0, hot = true),
    Metric("rushing_tds", "Rushing TDs", "RUSH TD", "volume",
        "Rushing touchdowns.", positions = RUSHERS, stability = 0.30, decimals = 0, hot = true),
    Metric("carry_share", "Carry Share", "CAR%", "volume",
        "Share of the team's carries taken by this player.",
        formula = "player_carries / team_carries", positions = RB,
        stability = 0.68, decimals = 3, hot = true),
    Metric("weighted_opportunities", "Weighted Opportunities", "WO", "volume",
        "Carries plus targets weighted by their relative PPR value. QB kneels " +
            "are not opportunities and are excluded.",
        formula = "carries (kneels excluded) + 2.6 * targets", positions = RB,
        predicts = "PPR fantasy points", decimals = 1, hot = true),
    Metric("opportunity_share", "Opportunity Share", "OPP%", "volume",
        "Share of the team's backfield carries and targets. Above 70% is a bellcow.",
        formula = "(carries + targets) / (team_rb_carries + team_rb_targets)",
        positions = RB, stability = 0.66, decimals = 3),
    Metric("rz_carries", "Red Zone Carries", "RZ CAR", "usage",
        "Carries starting inside the opponent 20, QB kneels excluded.",
        positions = QB_RB, decimals = 0, hot = true),
    Metric("gz_carries", "Green Zone Carries", "GZ CAR", "usage",
        "Carries starting inside the opponent 10, QB kneels excluded. Roughly " +
            "74% of rushing touchdowns originate here.",
        positions = QB_RB, predicts = "Rushing touchdowns", decimals = 0, hot = true),
    Metric("gl_carries", "Goal Line Carries", "GL CAR", "usage",
        "Carries starting inside the opponent 5, QB kneels excluded. Roughly " +
            "68% of rushing touchdowns originate here.",
        positions = QB_RB, predicts = "Rushing touchdowns", decimals = 0),
    Metric("rush_success_rate", "Rush Success Rate", "RSR", "efficiency",
        "Share of carries producing positive EPA. Far more stable than yards " +
            "per carry, which is notoriously noisy.",
        formula = "rushes with epa > 0 / carries", positions = QB_RB,
        stability = 0.44, decimals = 3),
    Metric("rush_epa_per_carry", "Rush EPA per Carry", "EPA/CAR", "efficiency",
        "Expected points added per rushing attempt.", positions = QB_RB, decimals = 3),

    // ---------------- Passing ----------------
    Metric("attempts", "Pass Attempts", "ATT", "passing",
        "Pass attempts.", positions = QB, decimals = 0, hot = true),
    Metric("completions", "Completions", "CMP", "passing",
        "Completed passes.", positions = QB, decimals = 0),
    Metric("passing_yards", "Passing Yards", "PASS YDS", "passing",
        "Total passing yards.", positions = QB, decimals = 0, hot = true),
    Metric("passing_tds", "Passing TDs", "PASS TD", "passing",
        "Passing touchdowns.", positions = QB, stability = 0.32, decimals = 0, hot = true),
    Metric("interceptions", "Interceptions", "INT", "passing",
        "Interceptions thrown.", positions = QB, higherIsBetter = false, decimals = 0, hot = true),
    Metric("sacks_taken", "Sacks Taken", "SK", "passing",
        "Times sacked.", positions = QB, higherIsBetter = false, decimals = 0),
    Metric("dropbacks", "Dropbacks", "DB", "passing",
        "Pass attempts plus sacks plus scrambles.", positions = QB, decimals = 0),
    Metric("epa_per_dropback", "EPA per Dropback", "EPA/DB", "efficiency",
        "Expected points added per dropback. The single best QB quality measure.",
        positions = QB, stability = 0.55, decimals = 3, hot = true),
    Metric("cpoe", "Completion % Over Expected", "CPOE", "efficiency",
        "Completion percentage above what the throw's difficulty predicts.",
        positions = QB, stability = 0.47, decimals = 2, hot = true),
    Metric("qb_rush_inside_5", "QB Carries Inside 5", "QB GL", "usage",
        "Designed QB runs inside the opponent 5 (no scrambles or kneels). The " +
            "biggest single source of QB fantasy separation.",
        positions = QB, predicts = "QB rushing touchdowns", decimals = 0),

    // ---------------- Snaps (tier B — auxiliary feed) ----------------
    Metric("offense_snaps", "Offensive Snaps", "SNAP", "volume",
        "Offensive snaps played.", tier = "B", decimals = 0, hot = true),
    Metric("snap_share", "Snap Share", "SNAP%", "volume",
        "Share of the team's offensive snaps played. The earliest reliable " +
            "signal of a role change, and the strongest waiver indicator.",
        formula = "offense_snaps / team_offense_snaps", tier = "B",
        predicts = "Role change before box score reflects it",
        stability = 0.79, decimals = 3, hot = true),

    // ---------------- Fantasy (computed on the device) ----------------
    Metric("fantasy_points", "Fantasy Points", "FPTS", "fantasy",
        "Points under the active scoring profile, scored game by game so " +
            "per-game bonuses apply to single games.", decimals = 1, computed = true),
    Metric("expected_fantasy_points", "Expected Fantasy Points", "xFP", "fantasy",
        "Points an average player would score from the same opportunities: " +
            "the active profile applied to the opportunity model's expected " +
            "receptions, yards, touchdowns and first downs.",
        predicts = "Future fantasy points, better than past points do",
        decimals = 1, computed = true),
    Metric("fpoe", "Fantasy Points Over Expected", "FPOE", "fantasy",
        "Actual fantasy points minus expected. Positive is a sell-high " +
            "signal, negative a buy-low signal. Long plays and fumbles have no " +
            "expectation, so they land here.",
        formula = "fantasy_points - expected_fantasy_points",
        predicts = "Negative regression when high", stability = 0.12,
        decimals = 1, computed = true),
    Metric("total_epa", "Total EPA", "EPA", "efficiency",
        "Expected points added across all touches.", decimals = 2),
) + SCORING_INPUTS.map {
    Metric(it.id, it.name, it.id.uppercase(), "fantasy", it.definition,
        decimals = it.decimals, isInternal = true, sparse = true)
} + RANGE_COMPONENTS.map {
    Metric(it.id, it.name, it.id.uppercase(), "context", it.definition,
        positions = ALL_POSITIONS, decimals = it.decimals, isInternal = true)
}

internal val SPARSE_METRIC_IDS: Set<String> = METRICS.filter { it.sparse }.mapTo(HashSet()) { it.id }
