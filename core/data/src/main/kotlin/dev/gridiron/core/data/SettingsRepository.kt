package dev.gridiron.core.data

import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.datastore.SeasonChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Which seasons the phone builds. Changes take effect on the next refresh: an
 * added season is fetched and crunched, and a removed season's rows are left
 * out of the new database.
 */
public class SettingsRepository(
    private val prefs: PrefsSource,
    private val currentSeason: () -> Int,
) {
    /** Every season the user can pick, oldest first. */
    public val choices: List<Int> get() = (FIRST_SEASON..currentSeason()).toList()

    /** The seasons to build, sorted and never empty. */
    public val seasons: Flow<List<Int>> =
        prefs.prefs.map { resolve(it.seasons, currentSeason()) }.distinctUntilChanged()

    /** Checks or unchecks [season]. Returns false, changing nothing, if that would leave no season checked. */
    public suspend fun setSelected(season: Int, selected: Boolean): Boolean {
        val current = currentSeason()
        require(season in FIRST_SEASON..current) { "season $season outside $FIRST_SEASON..$current" }
        var changed = false
        prefs.update { p ->
            val now = resolve(p.seasons, current)
            val next = if (selected) (now + season).distinct().sorted() else now - season
            if (next.isEmpty()) {
                p
            } else {
                changed = true
                p.copy(seasons = SeasonChoice(next, current))
            }
        }
        return changed
    }

    public companion object {
        /** nflverse's play-by-play goes back further, but ffopportunity's expected points start here. */
        public const val FIRST_SEASON: Int = 2012

        public fun defaults(current: Int): List<Int> = listOf(current - 2, current - 1, current)

        internal fun resolve(choice: SeasonChoice?, current: Int): List<Int> {
            val picked = when {
                choice == null -> defaults(current)
                choice.chosenIn in choice.seasons -> choice.seasons + ((choice.chosenIn + 1)..current)
                else -> choice.seasons
            }
            return picked.filter { it in FIRST_SEASON..current }.distinct().sorted().ifEmpty { defaults(current) }
        }
    }
}
