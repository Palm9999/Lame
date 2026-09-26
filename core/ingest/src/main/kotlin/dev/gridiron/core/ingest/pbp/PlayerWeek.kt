package dev.gridiron.core.ingest.pbp

/**
 * One player's week: the Kotlin twin of a row of the Python ETL's weekly
 * frame. A missing key and a null value both mean "no value" and are never
 * stored.
 */
internal class PlayerWeek(
    val season: Int,
    val week: Int,
    val team: String?,
    val playerId: String,
    val values: MutableMap<String, Double?>,
)

/**
 * Folds play-by-play into per player-week components, reproducing
 * `transform.weekly_player_stats` up to its derived columns (see `derive`).
 * Keys include the team, as in Python, so a player's row is per team.
 */
internal class PlayerWeekAggregator {
    private data class PlayerKey(val season: Int, val week: Int, val team: String, val playerId: String)
    private data class TeamKey(val season: Int, val week: Int, val team: String)

    private val players = LinkedHashMap<PlayerKey, Acc>()
    private val teams = HashMap<TeamKey, TeamAcc>()

    fun add(p: Play) {
        val team = p.posteam ?: return
        if (p.seasonType !in SEASON_TYPES || p.playType !in SCRIMMAGE_PLAY_TYPES) return
        val twoPoint = p.twoPointAttempt ?: 0.0
        if (twoPoint == 0.0) scrimmage(p, team) else if (twoPoint == 1.0) twoPoint(p, team)
    }

    fun rows(): List<PlayerWeek> = players.map { (k, acc) ->
        PlayerWeek(k.season, k.week, k.team, k.playerId, acc.columns(teams[TeamKey(k.season, k.week, k.team)]))
    }

    private fun acc(p: Play, team: String, playerId: String): Acc =
        players.getOrPut(PlayerKey(p.season, p.week, team, playerId)) { Acc() }

    private fun scrimmage(p: Play, team: String) {
        val eff = p.isEfficiency
        // Share denominators, on the same play set as their numerators: no dead-clock plays.
        val t = teams.getOrPut(TeamKey(p.season, p.week, team)) { TeamAcc() }
        if (p.receiver != null && eff) t.targets++
        if (eff) t.airYards += p.airYards ?: 0.0
        if (p.rusher != null && eff) t.carries++

        p.receiver?.let { acc(p, team, it).receiving(p) }
        p.rusher?.let { acc(p, team, it).rushing(p, eff) }
        p.passer?.let { acc(p, team, it).passing(p, eff) }

        // Credited to the ball carrier; a defender fumbling a return is not an offensive stat.
        val fumbler = p.fumbler
        if ((p.fumbleLost ?: 0.0) == 1.0 && fumbler != null &&
            (fumbler == p.rusher || fumbler == p.receiver || fumbler == p.passer)
        ) {
            acc(p, team, fumbler).fumblesLost++
        }
    }

    /** Successful two-point tries credit the passer and receiver, or the rusher. No targets or carries. */
    private fun twoPoint(p: Play, team: String) {
        if (p.twoPointResult != "success") return
        p.passer?.let { acc(p, team, it).passing2pt++ }
        p.receiver?.let { acc(p, team, it).receiving2pt++ }
        p.rusher?.let { acc(p, team, it).rushing2pt++ }
    }

    private class TeamAcc {
        var targets = 0.0
        var airYards = 0.0
        var carries = 0.0
    }

    private class Acc {
        var hasReceiving = false
        var targets = 0.0
        var receptions = 0.0
        var receivingYards = 0.0
        var airYards = 0.0
        var yac = 0.0
        var receivingTds = 0.0
        var rzTargets = 0.0
        var ezTargets = 0.0
        var recEpa = 0.0
        var receivingFirstDowns = 0.0
        var receivingTds40 = 0.0
        var receivingTds50 = 0.0

        var hasRushing = false
        var carries = 0.0
        var rushingYards = 0.0
        var rushingTds = 0.0
        var rushingFirstDowns = 0.0
        var rushingTds40 = 0.0
        var rushingTds50 = 0.0
        var rzCarries = 0.0
        var gzCarries = 0.0
        var glCarries = 0.0
        var qbRushInside5 = 0.0
        var rushEpa = 0.0
        var rushSuccesses = 0.0
        var carriesEff = 0.0

        var hasPassing = false
        var attempts = 0.0
        var completions = 0.0
        var passingYards = 0.0
        var passingTds = 0.0
        var interceptions = 0.0
        var sacksTaken = 0.0
        var passingFirstDowns = 0.0
        var passingTds40 = 0.0
        var passingTds50 = 0.0
        var dropbacks = 0.0
        var passEpa = 0.0
        var cpoeSum = 0.0
        var cpoeN = 0.0

        var fumblesLost = 0.0
        var passing2pt = 0.0
        var rushing2pt = 0.0
        var receiving2pt = 0.0

        fun receiving(p: Play) {
            hasReceiving = true
            targets++
            receptions += p.completePass ?: 0.0
            receivingYards += p.receivingYards ?: 0.0
            airYards += p.airYards ?: 0.0
            yac += p.yardsAfterCatch ?: 0.0
            receivingTds += p.passTouchdown ?: 0.0
            val yl = p.yardline100
            if (yl != null && yl <= 20) rzTargets++
            // A target thrown to or past the goal line.
            val air = p.airYards
            if (air != null && yl != null && air >= yl) ezTargets++
            recEpa += p.epa ?: 0.0
            receivingFirstDowns += p.firstDownPass ?: 0.0
            if (longTd(p.passTouchdown, p.receivingYards, 40)) receivingTds40++
            if (longTd(p.passTouchdown, p.receivingYards, 50)) receivingTds50++
        }

        fun rushing(p: Play, eff: Boolean) {
            hasRushing = true
            carries++
            rushingYards += p.rushingYards ?: 0.0
            rushingTds += p.rushTouchdown ?: 0.0
            rushingFirstDowns += p.firstDownRush ?: 0.0
            if (longTd(p.rushTouchdown, p.rushingYards, 40)) rushingTds40++
            if (longTd(p.rushTouchdown, p.rushingYards, 50)) rushingTds50++
            if (!eff) return
            val yl = p.yardline100
            if (yl != null && yl <= 20) rzCarries++
            if (yl != null && yl <= 10) gzCarries++
            if (yl != null && yl <= 5) glCarries++
            // Scrambles are rushing production but not designed usage.
            if (yl != null && yl <= 5 && (p.qbScramble ?: 0.0) == 0.0) qbRushInside5++
            rushEpa += p.epa ?: 0.0
            rushSuccesses += p.success ?: 0.0
            carriesEff++
        }

        fun passing(p: Play, eff: Boolean) {
            hasPassing = true
            attempts += p.passAttempt ?: 0.0
            completions += p.completePass ?: 0.0
            passingYards += p.passingYards ?: 0.0
            passingTds += p.passTouchdown ?: 0.0
            interceptions += p.interception ?: 0.0
            sacksTaken += p.sack ?: 0.0
            passingFirstDowns += p.firstDownPass ?: 0.0
            if (longTd(p.passTouchdown, p.passingYards, 40)) passingTds40++
            if (longTd(p.passTouchdown, p.passingYards, 50)) passingTds50++
            if (!eff) return
            val scramble = if ((p.qbScramble ?: 0.0) == 1.0) 1.0 else 0.0
            dropbacks += (p.passAttempt ?: 0.0) + (p.sack ?: 0.0) + scramble
            passEpa += p.epa ?: 0.0
            p.cpoe?.let {
                cpoeSum += it
                cpoeN++
            }
        }

        fun columns(t: TeamAcc?): MutableMap<String, Double?> = linkedMapOf<String, Double?>(
            "targets" to targets, "receptions" to receptions, "receiving_yards" to receivingYards,
            "air_yards" to airYards, "yac" to yac, "receiving_tds" to receivingTds,
            "rz_targets" to rzTargets, "ez_targets" to ezTargets,
            "receiving_first_downs" to receivingFirstDowns,
            "receiving_tds_40" to receivingTds40, "receiving_tds_50" to receivingTds50,
            "rec_epa" to recEpa.takeIf { hasReceiving },
            "carries" to carries, "rushing_yards" to rushingYards, "rushing_tds" to rushingTds,
            "rushing_first_downs" to rushingFirstDowns,
            "rushing_tds_40" to rushingTds40, "rushing_tds_50" to rushingTds50,
            "rz_carries" to rzCarries, "gz_carries" to gzCarries, "gl_carries" to glCarries,
            "qb_rush_inside_5" to qbRushInside5,
            "rush_epa" to rushEpa.takeIf { hasRushing },
            "rush_successes" to rushSuccesses, "carries_eff" to carriesEff,
            "attempts" to attempts, "completions" to completions, "passing_yards" to passingYards,
            "passing_tds" to passingTds, "interceptions" to interceptions, "sacks_taken" to sacksTaken,
            "passing_first_downs" to passingFirstDowns,
            "passing_tds_40" to passingTds40, "passing_tds_50" to passingTds50,
            "dropbacks" to dropbacks,
            "pass_epa" to passEpa.takeIf { hasPassing },
            "cpoe" to (if (hasPassing && cpoeN > 0) cpoeSum / cpoeN else null),
            "cpoe_sum" to cpoeSum.takeIf { hasPassing },
            "cpoe_n" to cpoeN,
            "fumbles_lost" to fumblesLost,
            "passing_2pt" to passing2pt, "rushing_2pt" to rushing2pt, "receiving_2pt" to receiving2pt,
            "team_targets" to t?.targets, "team_air_yards" to t?.airYards, "team_carries" to t?.carries,
        )
    }
}

/** A touchdown of at least [threshold] yards. A 55-yard score counts at 40 and at 50. */
private fun longTd(td: Double?, yards: Double?, threshold: Int): Boolean =
    (td ?: 0.0) == 1.0 && (yards ?: 0.0) >= threshold
