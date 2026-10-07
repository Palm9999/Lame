package dev.gridiron.core.forecast


/**
 * The chance a player nflverse lists Out or Doubtful plays each of his team's next [games] games (index 0 is the
 * listed game), from every past listing after a game he played whose whole window was played: once he is back he
 * counts as playing. [teamWeeks] is each team's played regular-season weeks by season. Null with fewer than
 * [K.RETURN_CURVE_MIN_CASES] listings.
 */
internal fun returnCurve(
    absent: Set<Triple<String, Int, Int>>,
    history: Map<String, List<PlayerGame>>,
    teamWeeks: Map<Pair<String, Int>, List<Int>>,
    games: Int = K.RETURN_CURVE_GAMES,
): DoubleArray? {
    val back = IntArray(games)
    var cases = 0
    for ((id, season, week) in absent) {
        val mine = history[id]?.filter { it.season == season } ?: continue
        val last = mine.lastOrNull { it.week < week } ?: continue
        val weeks = teamWeeks[last.team to season] ?: continue
        val at = weeks.indexOf(week)
        if (at <= 0 || weeks[at - 1] != last.week || at + games > weeks.size) continue
        val played = mine.mapTo(HashSet()) { it.week }
        cases++
        var returned = false
        for (k in 0 until games) {
            if (weeks[at + k] in played) returned = true
            if (returned) back[k]++
        }
    }
    if (cases < K.RETURN_CURVE_MIN_CASES) return null
    return DoubleArray(games) { back[it].toDouble() / cases }
}
