package dev.gridiron.feature.compare

import dev.gridiron.core.data.RadarUi
import dev.gridiron.core.data.ScatterPointUi
import java.util.Locale
import kotlin.math.abs

/** A spoken summary of the radar for TalkBack: who wins on how many axes. */
internal fun radarSummary(radar: RadarUi, names: List<String>, a: Int, b: Int): String {
    val pairs = radar.values[a].zip(radar.values[b]).filter { it.first != null && it.second != null }
    val wins = pairs.count { it.first!! > it.second!! }
    return "Radar: ${names[a]} higher on $wins of ${radar.axes.size} axes than ${names[b]}"
}

/** A spoken summary of the scatter for TalkBack: the population, then each compared slot. */
internal fun scatterSummary(slots: List<ScatterPointUi>, population: Int): String = buildString {
    append("Expected versus actual fantasy points per game for $population players.")
    for (p in slots) {
        val d = p.fpPerGame - p.xfpPerGame
        append(" ${p.name}: ${"%.1f".format(Locale.US, p.fpPerGame)} per game, ")
        append("${"%.1f".format(Locale.US, abs(d))} ${if (d >= 0) "above" else "below"} expected.")
    }
}
