package dev.gridiron.core.forecast

import androidx.sqlite.SQLiteConnection

/**
 * One RB, WR or TE's Rising roles signal entering [week]: how fast his role is growing, from games before it.
 * [score] is 0..100; the rest is why, for the screen's reason line.
 */
internal data class Signal(
    val playerId: String,
    val season: Int,
    val week: Int,
    val score: Double,
    /** Usage a game over the last games (RB: carries + targets; WR, TE: targets), then over the eight before them. */
    val usageRecent: Double,
    val usageBase: Double,
    /** Expected fantasy points a game over the same two spans; null when ffopportunity doesn't cover them. */
    val xpRecent: Double?,
    val xpBase: Double?,
    /** Usage of teammates out this week, as a share of his own recent usage, at most 1. */
    val vacated: Double,
    val outNote: String?,
)

/**
 * Rising roles. A role growing now tends to keep growing: in the 2024 and 2025 backtest the top 15% by this score kept a
 * 25%-larger role over the next four games about 55% of the time, against about 21% for everyone, while ranking by usage
 * level alone did no better than chance. It does not say a player will beat his projection, and it is not a points
 * forecast. Three signals, by [K] weights: usage up on his earlier games, expected points up on them (ffopportunity,
 * which lags the season, so with any of those weeks missing the score uses usage alone), and teammates out this week.
 */
internal object Breakout {
    private const val FIRST_WEEK = 2
    private const val LAST_WEEK = 18
    private const val MAX_GAP_WEEKS = 3

    fun compute(inputs: ForecastInputs): List<Signal> {
        val lastWeek = HashMap<Int, Int>()
        for (games in inputs.history.values) {
            for (g in games) if (g.week <= LAST_WEEK) lastWeek.merge(g.season, g.week, ::maxOf)
        }
        val absentByWeek = inputs.absent.groupBy({ it.second to it.third }, { it.first })
        val out = ArrayList<Signal>()
        for ((playerId, games) in inputs.history) {
            val info = inputs.players[playerId] ?: continue
            if (info.position == "QB") continue
            for ((season, last) in lastWeek) {
                for (week in FIRST_WEEK..minOf(last + 1, LAST_WEEK)) {
                    val prior = games.takeWhile { it.order < order(season, week) }
                    val signal = signal(inputs, info, prior, season, week) { room ->
                        teammatesOut(inputs, absentByWeek[season to week].orEmpty(), playerId, room, season, week)
                    }
                    if (signal != null) out += signal
                }
            }
        }
        return out
    }

    private fun usage(position: String, g: PlayerGame): Double = if (position == "RB") g["carries"] + g["targets"] else g["targets"]

    private fun expected(g: PlayerGame): Double =
        g["x_receptions"] + 0.1 * (g["x_receiving_yards"] + g["x_rushing_yards"]) + 6.0 * (g["x_receiving_tds"] + g["x_rushing_tds"])

    private fun rise(recent: Double, base: Double): Double = ((recent / base - 1.0) / K.BREAKOUT_FULL_RISE).coerceIn(0.0, 1.0)

    /** [prior]: his games before (season, week), oldest first; [out]: the teammates out that week, in the room he plays in. */
    private fun signal(
        inputs: ForecastInputs,
        info: PlayerInfo,
        prior: List<PlayerGame>,
        season: Int,
        week: Int,
        out: (room: Boolean) -> Pair<Double, String?>,
    ): Signal? {
        if (prior.size < K.BREAKOUT_RECENT + K.BREAKOUT_MIN_BASE) return null
        val last = prior.last()
        val gap = if (last.season == season) week - last.week else week + LAST_WEEK - last.week
        if (gap > MAX_GAP_WEEKS) return null
        val recent = prior.takeLast(K.BREAKOUT_RECENT)
        val base = prior.dropLast(K.BREAKOUT_RECENT).takeLast(K.BREAKOUT_BASE)
        val usageRecent = recent.sumOf { usage(info.position, it) } / recent.size
        val usageBase = base.sumOf { usage(info.position, it) } / base.size
        val floor = when (info.position) {
            "RB" -> K.BREAKOUT_MIN_USAGE_RB
            "WR" -> K.BREAKOUT_MIN_USAGE_WR
            else -> K.BREAKOUT_MIN_USAGE_TE
        }
        if (usageRecent < floor || usageBase <= 0.0) return null

        val covered = (recent + base).all { inputs.expectedThrough[it.season]?.let { through -> it.week <= through } == true }
        val xpBase = if (covered) base.sumOf(::expected) / base.size else null
        val xpRecent = if (xpBase != null && xpBase > 0.0) recent.sumOf(::expected) / recent.size else null
        val usageRise = rise(usageRecent, usageBase)
        val (vacated, note) = out(info.position == "RB").let { (usageOut, names) -> (usageOut / usageRecent).coerceIn(0.0, 1.0) to names }
        val blend = if (xpRecent != null && xpBase != null) {
            K.BREAKOUT_W_USAGE * usageRise + K.BREAKOUT_W_EXPECTED * rise(xpRecent, xpBase)
        } else {
            (K.BREAKOUT_W_USAGE + K.BREAKOUT_W_EXPECTED) * usageRise
        }
        val score = 100.0 * (blend + K.BREAKOUT_W_VACATED * vacated)
        return Signal(
            info.playerId, season, week, score, usageRecent, usageBase,
            xpRecent, xpBase.takeIf { xpRecent != null },
            vacated, note,
        )
    }

    /** Their recent usage a game, summed, and their names, for [absent] teammates on the player's team in his room. */
    private fun teammatesOut(
        inputs: ForecastInputs,
        absent: List<String>,
        playerId: String,
        runningBack: Boolean,
        season: Int,
        week: Int,
    ): Pair<Double, String?> {
        val mine = inputs.history[playerId]?.lastOrNull { it.order < order(season, week) }?.team ?: return 0.0 to null
        var total = 0.0
        val names = ArrayList<String>()
        for (id in absent) {
            if (id == playerId) continue
            val info = inputs.players[id] ?: continue
            if ((info.position == "RB") != runningBack || info.position == "QB") continue
            val games = inputs.history[id].orEmpty().filter { it.order < order(season, week) }.takeLast(K.BREAKOUT_RECENT)
            if (games.size < 3 || games.last().team != mine) continue
            total += games.sumOf { usage(info.position, it) } / games.size
            names += info.name
        }
        return total to names.take(3).joinToString(", ").ifEmpty { null }
    }
}

/** Inserts [Signal]s; the caller owns the transaction. */
internal class SignalWriter(conn: SQLiteConnection) : AutoCloseable {
    private val insert = conn.prepare(
        """INSERT OR REPLACE INTO player_week_signal
           (player_id, season, week, score, usage_recent, usage_base, xp_recent, xp_base, vacated, out_note)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
    )

    var rows: Long = 0
        private set

    fun write(s: Signal) {
        with(insert) {
            bindText(1, s.playerId)
            bindLong(2, s.season.toLong())
            bindLong(3, s.week.toLong())
            bindDouble(4, s.score)
            bindDouble(5, s.usageRecent)
            bindDouble(6, s.usageBase)
            if (s.xpRecent == null) bindNull(7) else bindDouble(7, s.xpRecent)
            if (s.xpBase == null) bindNull(8) else bindDouble(8, s.xpBase)
            bindDouble(9, s.vacated)
            if (s.outNote == null) bindNull(10) else bindText(10, s.outNote)
            step()
            reset()
        }
        rows++
    }

    override fun close() = insert.close()
}
