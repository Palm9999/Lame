package dev.gridiron.core.data.live

import dev.gridiron.core.data.GameState
import dev.gridiron.core.data.ScoresWeek
import dev.gridiron.core.model.normalCdf
import kotlin.math.sqrt

public object GameClock {
    private val RUNNING = Regex("""(\d{1,2}):(\d{2})\s*-\s*(1st|2nd|3rd|4th|OT)""", RegexOption.IGNORE_CASE)
    private val QUARTER = mapOf("1st" to 1, "2nd" to 2, "3rd" to 3, "4th" to 4)

    /**
     * How much of a game is left, 0 to 1: none once final, all before kickoff; live, from ESPN's short detail
     * ("4:14 - 3rd": the quarters left plus the clock, over 60 minutes; "Halftime" half; "End of 3rd" a quarter).
     * Overtime counts as none left; anything unreadable as half.
     */
    public fun shareLeft(state: GameState, detail: String?): Double {
        if (state == GameState.FINAL) return 0.0
        if (state == GameState.SCHEDULED) return 1.0
        val text = detail?.trim().orEmpty()
        RUNNING.find(text)?.let { m ->
            val quarter = QUARTER[m.groupValues[3].lowercase()] ?: return 0.0
            val minutes = m.groupValues[1].toInt() + m.groupValues[2].toInt() / 60.0
            return (((4 - quarter) * 15 + minutes) / 60.0).coerceIn(0.0, 1.0)
        }
        return when {
            text.contains("half", ignoreCase = true) -> 0.5
            text.startsWith("End of 1st", ignoreCase = true) -> 0.75
            text.startsWith("End of 2nd", ignoreCase = true) -> 0.5
            text.startsWith("End of 3rd", ignoreCase = true) -> 0.25
            text.startsWith("End of 4th", ignoreCase = true) || text.contains("OT", ignoreCase = true) -> 0.0
            else -> 0.5
        }
    }
}

/** What the live estimate knows of one player this week: his NFL team and his projection under the active profile. */
public data class LivePlayer(val team: String?, val points: Double, val sd: Double)

/** Both sides' live outlook: points so far (ESPN's), the expected finals, and the user's chance to win. */
public data class LiveWinChance(val myScore: Double, val theirScore: Double, val myExpected: Double, val theirExpected: Double, val chance: Double)

public object LiveWin {
    /**
     * Each starter (any slot but the bench and IR) ends at ESPN's points so far plus his projection times the share of
     * his game left ([GameClock]), give or take his spread times the square root of that share; a starter the app has
     * no projection for keeps only his points so far. The totals are compared as independent normals.
     */
    public fun estimate(mine: MatchupSide, theirs: MatchupSide, week: ScoresWeek, players: Map<String, LivePlayer>): LiveWinChance {
        val left = week.games.flatMap { g -> GameClock.shareLeft(g.state, g.detail).let { listOf(g.home to it, g.away to it) } }.toMap()

        fun side(s: MatchupSide): Triple<Double, Double, Double> {
            var scored = 0.0
            var expected = 0.0
            var variance = 0.0
            for (p in s.lineup.filter { it.slot != "BE" && it.slot != "IR" }) {
                val now = p.espnPoints ?: 0.0
                scored += now
                val info = p.playerId?.let(players::get)
                // A player whose team isn't on this week's schedule is on bye: nothing left.
                val share = info?.team?.let { left[it] } ?: 0.0
                expected += now + (info?.points ?: 0.0) * share
                variance += ((info?.sd ?: 0.0) * sqrt(share)).let { it * it }
            }
            return Triple(scored, expected, variance)
        }

        val (myScore, myExpected, myVar) = side(mine)
        val (theirScore, theirExpected, theirVar) = side(theirs)
        val sd = sqrt(myVar + theirVar)
        val margin = myExpected - theirExpected
        val chance = if (sd == 0.0) (if (margin > 0) 1.0 else if (margin < 0) 0.0 else 0.5) else normalCdf(margin / sd)
        return LiveWinChance(myScore, theirScore, myExpected, theirExpected, chance)
    }
}
