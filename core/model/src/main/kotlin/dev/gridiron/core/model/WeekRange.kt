package dev.gridiron.core.model

/**
 * An inclusive range of weeks within one season.
 *
 * nflverse numbers postseason weeks consecutively after the regular season, so
 * the regular season's last week depends on the year: the NFL moved from 17 to
 * 18 regular-season weeks in 2021. A hard-coded 1..18 would silently include
 * the 2020 Wild Card round in "regular season" totals.
 */
public data class WeekRange(val first: Int, val last: Int) {
    init {
        require(first in 1..MAX_WEEK) { "first week $first outside 1..$MAX_WEEK" }
        require(last in first..MAX_WEEK) { "last week $last outside $first..$MAX_WEEK" }
    }

    public val size: Int get() = last - first + 1

    public operator fun contains(week: Int): Boolean = week in first..last

    public companion object {
        /** Highest week number in the data: an 18-week season plus four playoff rounds. */
        public const val MAX_WEEK: Int = 22

        private const val FIRST_18_WEEK_SEASON = 2021

        public fun single(week: Int): WeekRange = WeekRange(week, week)

        public fun lastRegularSeasonWeek(season: Int): Int =
            if (season >= FIRST_18_WEEK_SEASON) 18 else 17

        public fun regularSeason(season: Int): WeekRange =
            WeekRange(1, lastRegularSeasonWeek(season))

        public fun postseason(season: Int): WeekRange =
            WeekRange(lastRegularSeasonWeek(season) + 1, lastRegularSeasonWeek(season) + 4)
    }
}
