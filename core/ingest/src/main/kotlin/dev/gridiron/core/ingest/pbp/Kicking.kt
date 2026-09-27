package dev.gridiron.core.ingest.pbp

/** Every kicking metric, zero until a kick adds to it. */
internal val KICKING_METRICS: List<String> = listOf(
    "fg_att", "fg_made", "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_made_50",
    "fg_missed", "xp_att", "xp_made", "xp_missed",
)

/** A field goal's distance bucket: `0_39`, `40_49` or `50`. An unknown distance counts as short. */
internal fun fgBucket(distance: Double?): String = when {
    distance == null || distance < 40 -> "0_39"
    distance < 50 -> "40_49"
    else -> "50"
}

/**
 * Folds field goals and extra points into kicker-weeks, reproducing
 * `transform.kicking_from`. Rows are keyed by team, like the other
 * aggregators', and every one has `g` = 1: a week with a kick is a week played.
 * A blocked or missed field goal is a miss; an extra point that isn't good
 * (failed, blocked or aborted) is a miss.
 */
internal class KickingAggregator {
    private data class Key(val season: Int, val week: Int, val team: String, val kicker: String)

    private val weeks = LinkedHashMap<Key, MutableMap<String, Double?>>()

    fun add(p: Play) {
        if (p.seasonType !in SEASON_TYPES) return
        val team = p.posteam ?: return
        val kicker = p.kicker ?: return
        val fieldGoal = p.fieldGoalAttempt == 1.0
        val extraPoint = p.extraPointAttempt == 1.0
        if (!fieldGoal && !extraPoint) return
        val v = weeks.getOrPut(Key(p.season, p.week, team, kicker)) {
            KICKING_METRICS.associateWithTo(LinkedHashMap<String, Double?>()) { 0.0 }.also { it["g"] = 1.0 }
        }
        fun count(id: String) {
            v[id] = (v[id] ?: 0.0) + 1.0
        }
        if (fieldGoal) {
            val bucket = fgBucket(p.kickDistance)
            count("fg_att")
            count("fg_att_$bucket")
            if (p.fieldGoalResult == "made") {
                count("fg_made")
                count("fg_made_$bucket")
            } else {
                count("fg_missed")
            }
        }
        if (extraPoint) {
            count("xp_att")
            if (p.extraPointResult == "good") count("xp_made") else count("xp_missed")
        }
    }

    fun rows(): List<PlayerWeek> = weeks.map { (k, v) -> PlayerWeek(k.season, k.week, k.team, k.kicker, v) }
}
