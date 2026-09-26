package dev.gridiron.core.ingest

import java.time.LocalDate

/** The NFL season in progress on [today]: a season is current from September. */
public fun currentSeason(today: LocalDate = LocalDate.now()): Int =
    if (today.monthValue >= 9) today.year else today.year - 1
