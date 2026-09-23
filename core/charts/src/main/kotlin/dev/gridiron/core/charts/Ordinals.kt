package dev.gridiron.core.charts

/**
 * "1st", "2nd", "3rd", "4th" … with the 11th-13th (and 111th-113th, etc.)
 * exception to the usual 1/2/3 suffixes. Shared by every percentile display
 * ([PercentileBarRow]'s spoken description, the Compare screen's bar and
 * table labels) so "92nd" never regresses back to "92th".
 */
public fun ordinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}
