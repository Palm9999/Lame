package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.CsvRow
import dev.gridiron.core.ingest.csv.readCsv
import dev.gridiron.core.ingest.pbp.Play
import dev.gridiron.core.ingest.pbp.PlayerWeek
import dev.gridiron.core.ingest.pbp.RATE_EXCLUDED_PLAY_TYPES
import dev.gridiron.core.ingest.pbp.SCRIMMAGE_PLAY_TYPES
import dev.gridiron.core.ingest.pbp.SEASON_TYPES
import java.io.InputStream

/**
 * FTN charting: nflverse's play-level flags (2022 on), one row per play. Twin of `etl/gridiron_etl/ftn.py`.
 *
 * FTN has no player ids, so a flag is credited through play-by-play: receiver flags to the target's
 * receiver, quarterback flags to the passer, the box count to the rusher. Every count sits beside FTN's own denominator (the plays
 * FTN charted), so a week play-by-play has but FTN lacks never drags a rate toward zero, and a range
 * recomputes as sum(count) / sum(denominator). Nothing is sparse: a week with no drops stores a 0.
 */

/** A charted play: play-by-play's `game_id` and `play_id`. */
internal data class FtnKey(val gameId: String, val playId: Int)

/** The flags this build reads for one play. */
internal class FtnFlags(
    val catchable: Boolean,
    val contested: Boolean,
    val drop: Boolean,
    val created: Boolean,
    val playAction: Boolean,
    val outOfPocket: Boolean,
    val throwAway: Boolean,
    val intWorthy: Boolean,
    val blitzers: Int,
    val screen: Boolean = false,
    val rpo: Boolean = false,
    val motion: Boolean = false,
    val noHuddle: Boolean = false,
    /** Defenders in the box; 0 where FTN didn't count them. */
    val box: Int = 0,
    /** Where the quarterback lined up: "S" shotgun, "U" under center, "P" pistol, "0" or blank uncharted. */
    val qbLocation: String = "",
    /** Pass rushers; 0 where FTN didn't count them. */
    val rushers: Int = 0,
    /** The read the pass went to: "1" first, "2" second, "CHK", "DES", "SD"; "0" or blank uncharted. */
    val read: String = "",
)

private val FLAG_COLUMNS = listOf(
    "is_catchable_ball", "is_contested_ball", "is_drop", "is_created_reception", "is_play_action",
    "is_qb_out_of_pocket", "is_throw_away", "is_interception_worthy", "n_blitzers",
    "is_screen_pass", "is_rpo", "is_motion", "is_no_huddle", "n_defense_box",
    "qb_location", "n_pass_rushers", "read_thrown",
)

/** Every play FTN charted, keyed like play-by-play. Any column missing from the file throws. */
internal fun readFtn(input: InputStream, source: String): Map<FtnKey, FtnFlags> {
    val index = HashMap<FtnKey, FtnFlags>()
    readCsv(input, source, listOf("nflverse_game_id", "nflverse_play_id") + FLAG_COLUMNS) { row ->
        val gameId = row.text("nflverse_game_id") ?: return@readCsv
        val playId = row.int("nflverse_play_id") ?: return@readCsv
        index[FtnKey(gameId, playId)] = FtnFlags(
            catchable = row.flag("is_catchable_ball"), contested = row.flag("is_contested_ball"),
            drop = row.flag("is_drop"), created = row.flag("is_created_reception"),
            playAction = row.flag("is_play_action"), outOfPocket = row.flag("is_qb_out_of_pocket"),
            throwAway = row.flag("is_throw_away"), intWorthy = row.flag("is_interception_worthy"),
            blitzers = row.int("n_blitzers") ?: 0,
            screen = row.flag("is_screen_pass"), rpo = row.flag("is_rpo"),
            motion = row.flag("is_motion"), noHuddle = row.flag("is_no_huddle"),
            box = row.int("n_defense_box") ?: 0,
            qbLocation = row.text("qb_location").orEmpty(),
            rushers = row.int("n_pass_rushers") ?: 0,
            read = row.text("read_thrown").orEmpty(),
        )
    }
    return index
}

private fun CsvRow.flag(name: String): Boolean = text(name) == "TRUE"

/**
 * Folds charted plays into per player-week FTN components. Same eligibility as
 * [dev.gridiron.core.ingest.pbp.PlayerWeekAggregator]: regular season and postseason scrimmage plays, no
 * two-point tries, no kneels or spikes. A play FTN did not chart counts nowhere, denominators included.
 */
internal class FtnAggregator(private val index: Map<FtnKey, FtnFlags>) {
    private data class Key(val season: Int, val week: Int, val team: String, val playerId: String)

    private class Acc {
        var targets = 0.0
        var catchable = 0.0
        var contested = 0.0
        var drops = 0.0
        var created = 0.0
        var dropbacks = 0.0
        var attempts = 0.0
        var playAction = 0.0
        var blitz = 0.0
        var outOfPocket = 0.0
        var throwAway = 0.0
        var intWorthy = 0.0
        var screenTargets = 0.0
        var motionTargets = 0.0
        var screen = 0.0
        var rpo = 0.0
        var noHuddle = 0.0
        var motion = 0.0
        var boxCarries = 0.0
        var boxSum = 0.0
        var shotgun = 0.0
        var rushersDb = 0.0
        var rushersSum = 0.0
        var readAttempts = 0.0
        var firstRead = 0.0

        fun columns(): MutableMap<String, Double?> {
            val values = LinkedHashMap<String, Double?>()
            if (targets > 0) {
                values["ftn_targets"] = targets
                values["ftn_catchable"] = catchable
                values["ftn_contested"] = contested
                values["ftn_drops"] = drops
                values["ftn_created_rec"] = created
                values["ftn_catchable_rate"] = catchable / targets
                values["ftn_drop_rate"] = drops / targets
                values["ftn_contested_rate"] = contested / targets
                values["ftn_screen_targets"] = screenTargets
                values["ftn_motion_targets"] = motionTargets
                values["ftn_screen_target_rate"] = screenTargets / targets
                values["ftn_motion_target_rate"] = motionTargets / targets
            }
            if (dropbacks > 0) {
                values["ftn_dropbacks"] = dropbacks
                values["ftn_attempts"] = attempts
                values["ftn_pa_db"] = playAction
                values["ftn_blitz_db"] = blitz
                values["ftn_oop_db"] = outOfPocket
                values["ftn_throwaway"] = throwAway
                values["ftn_int_worthy"] = intWorthy
                values["ftn_play_action_rate"] = playAction / dropbacks
                values["ftn_blitz_rate"] = blitz / dropbacks
                values["ftn_out_of_pocket_rate"] = outOfPocket / dropbacks
                values["ftn_throwaway_rate"] = throwAway / dropbacks
                if (attempts > 0) values["ftn_int_worthy_rate"] = intWorthy / attempts
                values["ftn_screen_db"] = screen
                values["ftn_rpo_db"] = rpo
                values["ftn_no_huddle_db"] = noHuddle
                values["ftn_motion_db"] = motion
                values["ftn_screen_rate"] = screen / dropbacks
                values["ftn_rpo_rate"] = rpo / dropbacks
                values["ftn_no_huddle_rate"] = noHuddle / dropbacks
                values["ftn_motion_rate"] = motion / dropbacks
                values["ftn_shotgun_db"] = shotgun
                values["ftn_shotgun_rate"] = shotgun / dropbacks
                values["ftn_rushers_db"] = rushersDb
                values["ftn_rushers_sum"] = rushersSum
                if (rushersDb > 0) values["ftn_avg_rushers"] = rushersSum / rushersDb
                values["ftn_read_att"] = readAttempts
                values["ftn_first_read"] = firstRead
                if (readAttempts > 0) values["ftn_first_read_rate"] = firstRead / readAttempts
            }
            if (boxCarries > 0) {
                values["ftn_box_carries"] = boxCarries
                values["ftn_box_sum"] = boxSum
                values["ftn_avg_box"] = boxSum / boxCarries
            }
            return values
        }
    }

    private val players = LinkedHashMap<Key, Acc>()

    /** Pass attempts that were eligible, and how many of them FTN charted: the coverage check's inputs. */
    var passAttempts = 0
        private set
    var chartedAttempts = 0
        private set

    fun add(p: Play) {
        val team = p.posteam ?: return
        if (p.seasonType !in SEASON_TYPES || p.playType !in SCRIMMAGE_PLAY_TYPES || p.playType in RATE_EXCLUDED_PLAY_TYPES) return
        if ((p.twoPointAttempt ?: 0.0) != 0.0) return
        val attempt = p.passAttempt ?: 0.0
        if (attempt > 0.0) passAttempts++
        val gameId = p.gameId ?: return
        val playId = p.playId ?: return
        val flags = index[FtnKey(gameId, playId)] ?: return
        if (attempt > 0.0) chartedAttempts++

        p.receiver?.let { receiver ->
            val a = acc(p, team, receiver)
            a.targets++
            if (flags.catchable) a.catchable++
            if (flags.contested) a.contested++
            if (flags.drop) a.drops++
            if (flags.created) a.created++
            if (flags.screen) a.screenTargets++
            if (flags.motion) a.motionTargets++
        }
        p.passer?.let { passer ->
            val scramble = if ((p.qbScramble ?: 0.0) == 1.0) 1.0 else 0.0
            val weight = attempt + (p.sack ?: 0.0) + scramble
            if (weight <= 0.0) return@let
            val a = acc(p, team, passer)
            a.dropbacks += weight
            a.attempts += attempt
            if (flags.playAction) a.playAction += weight
            if (flags.blitzers > 0) a.blitz += weight
            if (flags.outOfPocket) a.outOfPocket += weight
            if (flags.throwAway) a.throwAway += weight
            if (flags.intWorthy && attempt > 0.0) a.intWorthy += attempt
            if (flags.screen) a.screen += weight
            if (flags.rpo) a.rpo += weight
            if (flags.noHuddle) a.noHuddle += weight
            if (flags.motion) a.motion += weight
            if (flags.qbLocation == "S") a.shotgun += weight
            if (flags.rushers > 0) {
                a.rushersDb += weight
                a.rushersSum += weight * flags.rushers
            }
            if (attempt > 0.0 && flags.read.isNotEmpty() && flags.read != "0") {
                a.readAttempts += attempt
                if (flags.read == "1") a.firstRead += attempt
            }
        }
        p.rusher?.let { rusher ->
            if (flags.box <= 0) return@let
            val a = acc(p, team, rusher)
            a.boxCarries++
            a.boxSum += flags.box
        }
    }

    fun rows(): List<PlayerWeek> = players.map { (k, acc) -> PlayerWeek(k.season, k.week, k.team, k.playerId, acc.columns()) }
        .filter { it.values.isNotEmpty() }

    private fun acc(p: Play, team: String, playerId: String): Acc = players.getOrPut(Key(p.season, p.week, team, playerId)) { Acc() }
}
