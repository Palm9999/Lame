package dev.gridiron.app

import dev.gridiron.core.data.live.ActivityKind
import dev.gridiron.core.data.live.ActivityResult
import dev.gridiron.core.data.live.TradeOffer
import dev.gridiron.feature.projections.activityText
import dev.gridiron.feature.projections.tradeNet
import java.io.File
import java.util.Locale

/** One notification about the user's league: a trade made, a trade offered to them, or a waiver run. */
data class LeagueMoveAlert(val key: String, val title: String, val text: String)

/**
 * New moves in the user's league since the last check, each alerting once ([seen], one key a line): every executed
 * trade with each side's rest-of-season points in less out, each trade offered to the user, and each waiver run (all
 * the new claims in one alert, the user's first). The first check only remembers what's there.
 */
class LeagueMoveChecker(private val seen: File) {
    /** [ros] is each player's rest-of-season points under the user's scoring; [names] names the offers' players. */
    @Synchronized
    fun check(activity: ActivityResult, offers: List<TradeOffer>, ros: Map<String, Double>, names: Map<String, String>): List<LeagueMoveAlert> {
        val trades = activity.items.filter { it.kind == ActivityKind.TRADE }.map { "trade:${it.id}" to it }
        val claims = activity.items.filter { it.waiver }.map { "claim:${it.id}" to it }
        val offered = offers.filter { !it.fromMe }.map { "offer:${it.id}" to it }
        val keys = trades.map { it.first } + claims.map { it.first } + offered.map { it.first }
        val known = runCatching { seen.readLines().toSet() }.getOrNull()
        runCatching { seen.writeText((known.orEmpty() + keys).toList().takeLast(KEEP).joinToString("\n")) }
        if (known == null) return emptyList()
        fun team(id: Int) = activity.teams[id] ?: "Team $id"
        val alerts = trades.filter { it.first !in known }.map { (key, t) ->
            val net = tradeNet(t, ros).entries.sortedByDescending { it.value }.joinToString(", ") { (id, v) -> "${team(id)} ${signed(v)}" }
            LeagueMoveAlert(key, "Trade in your league", "${activityText(t, activity.teams)}. Rest of season: $net.")
        } + offered.filter { it.first !in known }.map { (key, o) ->
            fun list(ids: List<String>) = ids.joinToString(" + ") { names[it] ?: it }.ifEmpty { "nothing" }
            val gain = o.get.sumOf { ros[it] ?: 0.0 } - o.give.sumOf { ros[it] ?: 0.0 }
            LeagueMoveAlert(key, "Trade offer from ${o.partner}", "${list(o.get)} for your ${list(o.give)}: ${signed(gain)} rest-of-season points for you. Weigh it in Trade.")
        }
        val fresh = claims.filter { it.first !in known }.map { it.second }
            .sortedWith(compareByDescending<dev.gridiron.core.data.live.ActivityItem> { it.teamId == activity.myTeamId }.thenByDescending { it.bid ?: 0 })
        if (fresh.isEmpty()) return alerts
        val text = fresh.take(SHOWN).joinToString("; ") { activityText(it, activity.teams) } +
            (if (fresh.size > SHOWN) "; and ${fresh.size - SHOWN} more" else "") + "."
        return alerts + LeagueMoveAlert("waivers:${fresh.first().id}", "Waivers ran: ${fresh.size} claim${if (fresh.size == 1) "" else "s"}", text)
    }

    private companion object {
        /** Claims named in a waiver alert; the rest are counted. */
        const val SHOWN = 4

        /** Plenty for a season's trades, offers and claims. */
        const val KEEP = 2_000
    }
}

/** "+12.3" or "−4.1". */
internal fun signed(v: Double): String = (if (v >= 0) "+" else "−") + String.format(Locale.US, "%.1f", kotlin.math.abs(v))
