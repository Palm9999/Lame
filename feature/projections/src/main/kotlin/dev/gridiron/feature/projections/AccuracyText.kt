package dev.gridiron.feature.projections

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** [value] to [places] decimals. A value that rounds to zero never shows as "-0.0". */
internal fun fixed(value: Double, places: Int): String {
    val clean = if (abs(value) < 0.5 / 10.0.pow(places)) 0.0 else value
    return String.format(Locale.US, "%.${places}f", clean)
}

/** A mean error with its sign: "+0.4", "-1.2", or "0.0". */
internal fun signed(value: Double): String = fixed(value, 1).let { if (it.startsWith("-") || it == "0.0") it else "+$it" }

/** R² to two decimals, or a dash when the actual scores had no spread. */
internal fun r2Text(r2: Double?): String = r2?.let { fixed(it, 2) } ?: "—"

internal fun percent(share: Double): String = "${(share * 100).roundToInt()}%"
