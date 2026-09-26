package dev.gridiron.core.ingest.validate

import dev.gridiron.core.ingest.ExpectedRow
import dev.gridiron.core.ingest.pbp.PlayerWeek
import kotlin.math.abs

/**
 * `validate.ComparisonPolicy`. A row beyond [tight] is an outlier: a warning
 * a person can look at, not a failure. A row beyond [hardCap], or more than
 * [seasonBudget] outliers in one season, fails the build: that's a dropped or
 * doubled category, or systematic drift, not an isolated upstream quirk.
 */
internal data class ComparisonPolicy(val name: String, val tight: Double, val hardCap: Double, val seasonBudget: Int)

internal fun <R> applyPolicy(
    rows: List<R>,
    season: (R) -> Int,
    magnitude: (R) -> Double,
    describe: (R) -> String,
    policy: ComparisonPolicy,
    warnings: MutableList<String>,
): List<String> {
    val outliers = rows.filter { magnitude(it) > policy.tight }
    if (outliers.isEmpty()) return emptyList()
    outliers.forEach { warnings += "ffopportunity outlier [${policy.name}]: ${describe(it)}" }

    val problems = mutableListOf<String>()
    val overCap = outliers.filter { magnitude(it) > policy.hardCap }
    if (overCap.isNotEmpty()) {
        problems += "${policy.name}: ${overCap.size} player-week(s) exceed the hard cap of ${policy.hardCap} " +
            "(tight tolerance ${policy.tight}), e.g. ${overCap.take(3).map(describe)}"
    }
    outliers.groupingBy(season).eachCount().toSortedMap().forEach { (s, n) ->
        if (n > policy.seasonBudget) {
            problems += "${policy.name}: season $s has $n outliers beyond tolerance ${policy.tight}, " +
                "exceeding the season budget of ${policy.seasonBudget} — likely systematic drift " +
                "rather than isolated upstream quirks"
        }
    }
    return problems
}

private class Joined(val ours: PlayerWeek, val theirs: ExpectedRow) {
    fun our(metric: String): Double = ours.values[metric] ?: 0.0
    fun their(column: String): Double = theirs.value(column)
    fun describe(vararg values: Pair<String, Double>): String =
        "player_id=${ours.playerId}, season=${ours.season}, week=${ours.week}, " +
            values.joinToString { (k, v) -> "$k=$v" }
}

private fun join(weekly: List<PlayerWeek>, ep: List<ExpectedRow>): List<Joined> {
    val theirs = ep.associateBy { Triple(it.playerId, it.season, it.week) }
    return weekly.mapNotNull { w -> theirs[Triple(w.playerId, w.season, w.week)]?.let { Joined(w, it) } }
}

private const val CROSS_CHECK_TOLERANCE = 1.0
/** Backward laterals: nflverse credits the passer with yards after the pitch; ffopportunity doesn't. */
private const val LATERAL_YARDS_TOLERANCE = 45.0
private const val COUNT_HARD_CAP = 3.0
private const val VOLUME_HARD_CAP = 5.0
private const val YARDAGE_HARD_CAP = 50.0
private const val LATERAL_HARD_CAP = 100.0
private const val DEFAULT_SEASON_BUDGET = 5

private fun pair(ours: String, theirs: String, tolerance: Double, hardCap: Double) =
    Triple(ours, theirs, ComparisonPolicy("cross-check $ours vs ffopportunity $theirs", tolerance, hardCap, DEFAULT_SEASON_BUDGET))

/** Our play-by-play actual, ffopportunity's actual for the same stat, and the policy for the pair. */
private val CROSS_CHECK_PAIRS = listOf(
    pair("receptions", "receptions", CROSS_CHECK_TOLERANCE, VOLUME_HARD_CAP),
    pair("completions", "pass_completions", CROSS_CHECK_TOLERANCE, VOLUME_HARD_CAP),
    pair("passing_yards", "pass_yards_gained", LATERAL_YARDS_TOLERANCE, LATERAL_HARD_CAP),
    pair("rushing_yards", "rush_yards_gained", CROSS_CHECK_TOLERANCE, YARDAGE_HARD_CAP),
    pair("receiving_yards", "rec_yards_gained", CROSS_CHECK_TOLERANCE, YARDAGE_HARD_CAP),
    pair("passing_tds", "pass_touchdown", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
    pair("rushing_tds", "rush_touchdown", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
    pair("receiving_tds", "rec_touchdown", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
    pair("passing_2pt", "pass_two_point_conv", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
    pair("rushing_2pt", "rush_two_point_conv", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
    pair("receiving_2pt", "rec_two_point_conv", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
    pair("passing_first_downs", "pass_first_down", CROSS_CHECK_TOLERANCE, VOLUME_HARD_CAP),
    pair("rushing_first_downs", "rush_first_down", CROSS_CHECK_TOLERANCE, VOLUME_HARD_CAP),
    pair("receiving_first_downs", "rec_first_down", CROSS_CHECK_TOLERANCE, VOLUME_HARD_CAP),
    pair("interceptions", "pass_interception", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
)

/**
 * Ours also counts sack fumbles, so only trailing theirs matters, and any
 * trailing is an outlier. Traced upstream misattributions trail by exactly 1,
 * about 7 a season at worst; a real regression blows the budget of 20.
 */
internal val FUMBLE_POLICY: ComparisonPolicy = ComparisonPolicy("cross-check fumbles_lost vs ffopportunity", 0.0, 3.0, 20)

private val FANTASY_CONTRACT_POLICY = ComparisonPolicy("fantasy contract: total_fantasy_points vs our components", 2.05, 5.5, 5)
private val EXPECTED_FANTASY_CONTRACT_POLICY =
    ComparisonPolicy("fantasy contract: total_fantasy_points_exp vs expected components", 0.22, 6.0, 5)

/** Our play-by-play actuals against ffopportunity's, per player-week. */
internal fun crossCheck(weekly: List<PlayerWeek>, ep: List<ExpectedRow>, warnings: MutableList<String>): List<String> {
    val joined = join(weekly, ep)
    val problems = mutableListOf<String>()
    for ((ours, theirs, policy) in CROSS_CHECK_PAIRS) {
        problems += applyPolicy(
            joined, { it.ours.season }, { abs(it.our(ours) - it.their(theirs)) },
            { it.describe(ours to it.our(ours), theirs to it.their(theirs)) }, policy, warnings,
        )
    }
    fun theirFumbles(j: Joined) = j.their("rec_fumble_lost") + j.their("rush_fumble_lost")
    problems += applyPolicy(
        joined, { it.ours.season }, { maxOf(0.0, theirFumbles(it) - it.our("fumbles_lost")) },
        { it.describe("fumbles_lost" to it.our("fumbles_lost"), "ffopportunity fumbles" to theirFumbles(it)) },
        FUMBLE_POLICY, warnings,
    )
    return problems
}

/** The profile ffopportunity's totals use, fumbles excluded. */
private fun referencePoints(
    rec: Double, recYds: Double, recTd: Double, rec2pt: Double,
    rushYds: Double, rushTd: Double, rush2pt: Double,
    passYds: Double, passTd: Double, pass2pt: Double, ints: Double,
): Double = rec + 0.1 * recYds + 6 * recTd + 2 * rec2pt +
    0.1 * rushYds + 6 * rushTd + 2 * rush2pt +
    0.04 * passYds + 4 * passTd + 2 * pass2pt - 2 * ints

/** Our components, scored with ffopportunity's rules, reproduce its totals; same for expected. */
internal fun fantasyContract(weekly: List<PlayerWeek>, ep: List<ExpectedRow>, warnings: MutableList<String>): List<String> {
    fun ourPoints(j: Joined) = referencePoints(
        j.our("receptions"), j.our("receiving_yards"), j.our("receiving_tds"), j.our("receiving_2pt"),
        j.our("rushing_yards"), j.our("rushing_tds"), j.our("rushing_2pt"),
        j.our("passing_yards"), j.our("passing_tds"), j.our("passing_2pt"), j.our("interceptions"),
    )
    // Fumbles are removed from both sides: ours include sack fumbles, theirs don't.
    fun theirPoints(j: Joined) = j.their("total_fantasy_points") + 2 * (j.their("rec_fumble_lost") + j.their("rush_fumble_lost"))

    val problems = applyPolicy(
        join(weekly, ep), { it.ours.season }, { abs(ourPoints(it) - theirPoints(it)) },
        { it.describe("total_fantasy_points" to it.their("total_fantasy_points"), "our_points" to ourPoints(it)) },
        FANTASY_CONTRACT_POLICY, warnings,
    ).toMutableList()

    fun expectedPoints(e: ExpectedRow): Double {
        val x = e.toPlayerWeek().values
        fun c(k: String) = x[k] ?: 0.0
        return referencePoints(
            c("x_receptions"), c("x_receiving_yards"), c("x_receiving_tds"), c("x_receiving_2pt"),
            c("x_rushing_yards"), c("x_rushing_tds"), c("x_rushing_2pt"),
            c("x_passing_yards"), c("x_passing_tds"), c("x_passing_2pt"), c("x_interceptions"),
        )
    }
    problems += applyPolicy(
        ep, { it.season }, { abs(expectedPoints(it) - it.value("total_fantasy_points_exp")) },
        {
            "player_id=${it.playerId}, season=${it.season}, week=${it.week}, " +
                "total_fantasy_points_exp=${it.value("total_fantasy_points_exp")}, our_expected_points=${expectedPoints(it)}"
        },
        EXPECTED_FANTASY_CONTRACT_POLICY, warnings,
    )
    return problems
}

/**
 * The week through which ffopportunity covers the season's play-by-play, and
 * a warning when it lags: after Monday Night Football, a build can run before
 * ffopportunity has processed the week, and xFP is 0 there until it catches up.
 */
internal fun expectedCoverage(season: Int, weekly: List<PlayerWeek>, ep: List<ExpectedRow>?): Pair<Int, String?> {
    val played = weekly.map { it.week }.distinct().sorted()
    val covered = ep.orEmpty().map { it.week }.toSet()
    val missing = played.filter { it !in covered }
    val warning = if (missing.isEmpty()) null else {
        val (label, verb) = if (missing.size == 1) "week" to "has" else "weeks" to "have"
        "season $season: play-by-play $label ${missing.joinToString(", ")} $verb games but no ffopportunity " +
            "expected rows; xFP is 0 (FPOE = FP) there until ffopportunity catches up"
    }
    var through = 0
    for (w in played) {
        if (w !in covered) break
        through = w
    }
    return through to warning
}
