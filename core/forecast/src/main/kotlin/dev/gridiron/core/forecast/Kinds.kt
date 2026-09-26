package dev.gridiron.core.forecast

/** Which part of the offense a stat comes from: game script moves passing and rushing apart. */
internal enum class Side { PASS, RUSH, TOUCH }

/** How a stat responds to an adjustment: TDs most, yards less, opportunities and counts least. */
internal enum class StatType { VOLUME, COUNT, YARDS, TD }

internal val KINDS: Map<String, Pair<Side, StatType>> = buildMap {
    fun kind(side: Side, type: StatType, vararg ids: String) = ids.forEach { put(it, side to type) }
    kind(Side.PASS, StatType.VOLUME, "attempts", "targets")
    kind(Side.PASS, StatType.COUNT, "completions", "interceptions", "sacks_taken", "passing_first_downs", "receptions", "receiving_first_downs")
    kind(Side.PASS, StatType.YARDS, "passing_yards", "receiving_yards")
    kind(
        Side.PASS, StatType.TD,
        "passing_tds", "passing_tds_40", "passing_tds_50", "passing_2pt",
        "receiving_tds", "receiving_tds_40", "receiving_tds_50", "receiving_2pt",
    )
    kind(Side.RUSH, StatType.VOLUME, "carries")
    kind(Side.RUSH, StatType.COUNT, "rushing_first_downs")
    kind(Side.RUSH, StatType.YARDS, "rushing_yards")
    kind(Side.RUSH, StatType.TD, "rushing_tds", "rushing_tds_40", "rushing_tds_50", "rushing_2pt")
    kind(Side.TOUCH, StatType.COUNT, "fumbles_lost")
}

/** Multiplies each stat by what [multiplier] gives its kind. */
internal fun adjust(components: Map<String, Double>, multiplier: (Side, StatType) -> Double): Map<String, Double> =
    components.mapValues { (id, value) ->
        val (side, type) = KINDS.getValue(id)
        value * multiplier(side, type)
    }

/**
 * Fixed full-PPR points, used only to split a projection's change between
 * matchup and game script and to decide which rows are worth storing. The app
 * scores every projection with the user's own profile.
 */
internal fun referencePoints(components: Map<String, Double>): Double {
    fun v(id: String) = components[id] ?: 0.0
    return 0.04 * v("passing_yards") + 4 * v("passing_tds") - 2 * v("interceptions") +
        0.1 * v("rushing_yards") + 6 * v("rushing_tds") +
        v("receptions") + 0.1 * v("receiving_yards") + 6 * v("receiving_tds") +
        2 * (v("passing_2pt") + v("rushing_2pt") + v("receiving_2pt")) - 2 * v("fumbles_lost")
}

internal fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) {
        "th"
    } else {
        when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }
    return "$n$suffix"
}
