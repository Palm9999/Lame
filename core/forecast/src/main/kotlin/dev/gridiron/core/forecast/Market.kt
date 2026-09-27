package dev.gridiron.core.forecast

/** A player placed on a team for the upcoming week, whom a prop could name. */
internal data class PropCandidate(val playerId: String, val name: String, val team: String)

/**
 * Props matched to players for one week (spec §5: "normalized name plus
 * team"). An event counts when it is one of [games], in either team order
 * (neutral sites). A name matches when exactly one candidate on the event's
 * two teams has it after [normalizeName]; two players with one name match
 * neither, so a prop is never given to the wrong player.
 */
internal class MarketMatch(snapshot: PropsSnapshot, games: Collection<Pair<String, String>>, candidates: List<PropCandidate>) {
    private val byPlayer = HashMap<String, MutableList<PropQuote>>()

    /** Names (one per game) that no single player matched, including every name in a game that isn't this week's. */
    val unmatched: Int

    init {
        val pairs = games.map { (home, away) -> setOf(home, away) }.toSet()
        var missed = 0
        for (event in snapshot.events) {
            val teams = setOf(event.home, event.away)
            val byName = event.quotes.groupBy { normalizeName(it.player) }
            if (teams !in pairs) {
                missed += byName.size
                continue
            }
            val onTeams = candidates.filter { it.team in teams }.groupBy { normalizeName(it.name) }
            for ((name, quotes) in byName) {
                val who = onTeams[name]?.singleOrNull()
                if (who == null) {
                    missed++
                    continue
                }
                byPlayer.getOrPut(who.playerId) { mutableListOf() } += quotes
            }
        }
        unmatched = missed
    }

    /** Every quote naming [playerId], or null when none does. */
    fun quotes(playerId: String): List<PropQuote>? = byPlayer[playerId]
}

/** A projection after props, and the `market` factor's note ("Props: 64.5 rec yds, TD 38%"). */
internal class Blended(val components: Map<String, Double>, val note: String)

/**
 * [final] with each priced stat blended toward the market ([blendMean]).
 * The anytime-TD market prices rushing plus receiving TDs: the blended total
 * is split by the model's own split, or, when the model had none, given to
 * rushing for QBs and RBs and receiving for WRs and TEs. Targets rise to
 * meet receptions the market pushed past them, so no catch rate tops 100%.
 * Null when no quote is usable.
 */
internal fun blend(final: Map<String, Double>, quotes: List<PropQuote>, position: String): Blended? {
    val cv = K.EMPIRICAL_CV.getValue(position)
    val views = marketViews(quotes, cv)
    if (views.isEmpty()) return null
    val out = final.toMutableMap()
    for (view in views) {
        if (view.market == ANYTIME_TD) {
            val rushing = final["rushing_tds"] ?: 0.0
            val receiving = final["receiving_tds"] ?: 0.0
            val model = rushing + receiving
            val total = blendMean(model, view.mean, cv)
            if (model > 0.0) {
                if (rushing > 0.0) out["rushing_tds"] = rushing * total / model
                if (receiving > 0.0) out["receiving_tds"] = receiving * total / model
            } else {
                out[if (position == "WR" || position == "TE") "receiving_tds" else "rushing_tds"] = total
            }
        } else {
            val stat = MARKET_STATS.getValue(view.market)
            out[stat] = blendMean(final[stat] ?: 0.0, view.mean, cv)
        }
    }
    val targets = out["targets"]
    val receptions = out["receptions"]
    if (targets != null && receptions != null && receptions > targets) out["targets"] = receptions
    return Blended(out, "Props: " + views.joinToString(", ") { it.label })
}
