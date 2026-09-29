package dev.gridiron.core.ingest.pbp

import dev.gridiron.core.ingest.csv.CsvRow
import dev.gridiron.core.ingest.csv.readCsv
import java.io.InputStream

internal val SEASON_TYPES: Set<String> = setOf("REG", "POST")

/**
 * Scrimmage plays that carry official rushing/passing stats. Kneels and spikes
 * are real attempts (nflverse marks them rush_attempt/pass_attempt = 1), so
 * box-score totals and scoring inputs include them.
 */
internal val SCRIMMAGE_PLAY_TYPES: Set<String> = setOf("pass", "run", "qb_kneel", "qb_spike")

/** Counted for box-score volume, excluded from every rate, share and usage signal. */
internal val RATE_EXCLUDED_PLAY_TYPES: Set<String> = setOf("qb_kneel", "qb_spike")

/** Play-by-play columns the build reads. Every one must exist in the file. */
internal val PBP_COLUMNS: List<String> = listOf(
    "season", "week", "season_type", "game_id", "posteam", "defteam", "play_type",
    "pass_attempt", "complete_pass", "air_yards", "yards_after_catch", "yards_gained",
    "passing_yards", "receiving_yards", "rushing_yards", "pass_touchdown", "rush_touchdown",
    "interception", "sack", "qb_scramble", "receiver_player_id", "rusher_player_id",
    "passer_player_id", "yardline_100", "epa", "success", "cpoe", "two_point_attempt",
    "first_down_pass", "first_down_rush", "fumble_lost", "fumbled_1_player_id",
    "two_point_conv_result", "touchdown", "td_team", "home_team", "away_team",
    "total_home_score", "total_away_score",
    "kicker_player_id", "field_goal_attempt", "field_goal_result", "kick_distance",
    "extra_point_attempt", "extra_point_result", "safety", "posteam_score", "posteam_score_post",
    "play_id",
)

internal class Play(
    val season: Int,
    val week: Int,
    val seasonType: String?,
    val gameId: String?,
    val posteam: String?,
    val defteam: String?,
    val playType: String?,
    val passAttempt: Double?,
    val completePass: Double?,
    val airYards: Double?,
    val yardsAfterCatch: Double?,
    val yardsGained: Double?,
    val passingYards: Double?,
    val receivingYards: Double?,
    val rushingYards: Double?,
    val passTouchdown: Double?,
    val rushTouchdown: Double?,
    val interception: Double?,
    val sack: Double?,
    val qbScramble: Double?,
    val receiver: String?,
    val rusher: String?,
    val passer: String?,
    val yardline100: Double?,
    val epa: Double?,
    val success: Double?,
    val cpoe: Double?,
    val twoPointAttempt: Double?,
    val firstDownPass: Double?,
    val firstDownRush: Double?,
    val fumbleLost: Double?,
    val fumbler: String?,
    val twoPointResult: String?,
    val touchdown: Double?,
    val tdTeam: String?,
    val homeTeam: String?,
    val awayTeam: String?,
    val totalHomeScore: Double?,
    val totalAwayScore: Double?,
    val kicker: String?,
    val fieldGoalAttempt: Double?,
    /** "made", "missed" or "blocked". */
    val fieldGoalResult: String?,
    val kickDistance: Double?,
    val extraPointAttempt: Double?,
    /** "good", "failed", "blocked" or "aborted". */
    val extraPointResult: String?,
    val safety: Double?,
    /** posteam's score before and after the play: which side a safety scored for. */
    val posteamScore: Double?,
    val posteamScorePost: Double?,
    /** With [gameId], the key FTN charting joins on. */
    val playId: Int? = null,
) {
    /** False for kneels and spikes: see [RATE_EXCLUDED_PLAY_TYPES]. */
    val isEfficiency: Boolean get() = playType !in RATE_EXCLUDED_PLAY_TYPES
}

private fun CsvRow.toPlay(): Play? {
    val season = int("season") ?: return null
    val week = int("week") ?: return null
    return Play(
        season = season, week = week, seasonType = text("season_type"), gameId = text("game_id"),
        posteam = text("posteam"), defteam = text("defteam"), playType = text("play_type"),
        passAttempt = double("pass_attempt"), completePass = double("complete_pass"),
        airYards = double("air_yards"), yardsAfterCatch = double("yards_after_catch"),
        yardsGained = double("yards_gained"), passingYards = double("passing_yards"),
        receivingYards = double("receiving_yards"), rushingYards = double("rushing_yards"),
        passTouchdown = double("pass_touchdown"), rushTouchdown = double("rush_touchdown"),
        interception = double("interception"), sack = double("sack"),
        qbScramble = double("qb_scramble"), receiver = text("receiver_player_id"),
        rusher = text("rusher_player_id"), passer = text("passer_player_id"),
        yardline100 = double("yardline_100"), epa = double("epa"), success = double("success"),
        cpoe = double("cpoe"), twoPointAttempt = double("two_point_attempt"),
        firstDownPass = double("first_down_pass"), firstDownRush = double("first_down_rush"),
        fumbleLost = double("fumble_lost"), fumbler = text("fumbled_1_player_id"),
        twoPointResult = text("two_point_conv_result"), touchdown = double("touchdown"),
        tdTeam = text("td_team"), homeTeam = text("home_team"), awayTeam = text("away_team"),
        totalHomeScore = double("total_home_score"), totalAwayScore = double("total_away_score"),
        kicker = text("kicker_player_id"), fieldGoalAttempt = double("field_goal_attempt"),
        fieldGoalResult = text("field_goal_result"), kickDistance = double("kick_distance"),
        extraPointAttempt = double("extra_point_attempt"), extraPointResult = text("extra_point_result"),
        safety = double("safety"), posteamScore = double("posteam_score"), posteamScorePost = double("posteam_score_post"),
        playId = int("play_id"),
    )
}

/** Streams every play in a play-by-play file. Each aggregator applies its own filter. */
internal fun readPlays(input: InputStream, source: String, onPlay: (Play) -> Unit) {
    readCsv(input, source, PBP_COLUMNS) { row -> row.toPlay()?.let(onPlay) }
}
