package dev.gridiron.app

import java.io.File
import kotlin.math.abs

/**
 * The week's win chances as Home saw them, oldest first, for its line: one "season week chance" line each in a small
 * file, so the line survives leaving the app. A new week starts it again.
 */
class WinLineStore(private val file: File) {
    /** Adds [chance] (when not null and not the same as the last) to [season] [week]'s line and returns the line. */
    @Synchronized
    fun record(season: Int, week: Int, chance: Double?): List<Double> {
        val key = "$season $week "
        var line = runCatching { file.readLines() }.getOrDefault(emptyList())
            .filter { it.startsWith(key) }
            .mapNotNull { it.removePrefix(key).toDoubleOrNull() }
        if (chance != null && line.lastOrNull()?.let { abs(it - chance) < SAME } != true) {
            line = (line + chance).takeLast(MAX_POINTS)
            runCatching { file.writeText(line.joinToString("") { "$key$it\n" }) }
        }
        return line
    }

    private companion object {
        /** Under a tenth of a point of chance reads as no change. */
        const val SAME = 0.001

        /** A minute each through a long Sunday and then some. */
        const val MAX_POINTS = 600
    }
}
