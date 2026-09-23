package dev.gridiron.core.data

import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.WeekRange

/** "Wk 1–8", clipped to weeks that have been played; "Week 3" for one week. */
public fun weeksLabel(season: SeasonInfo, weeks: WeekRange): String {
    if (weeks.first > season.lastWeek) return "Wk ${weeks.first}–${weeks.last}, not played yet"
    val last = minOf(weeks.last, season.lastWeek)
    return if (weeks.first == last) "Week ${weeks.first}" else "Wk ${weeks.first}–$last"
}

/** "2025 · Wk 1–8", or "2023 · no data" once a season has left the database. */
public fun describeSlot(slot: CompareSlot, catalog: Catalog): String {
    val info = catalog.seasons.firstOrNull { it.season == slot.season } ?: return "${slot.season} · no data"
    return "${slot.season} · ${weeksLabel(info, slot.weeks)}"
}
