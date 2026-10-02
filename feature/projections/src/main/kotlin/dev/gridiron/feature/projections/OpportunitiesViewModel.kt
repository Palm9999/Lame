package dev.gridiron.feature.projections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.OpportunitiesRepository
import dev.gridiron.core.data.OpportunitiesResult
import dev.gridiron.core.data.OpportunityRow
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.model.ScoringProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public sealed interface OpportunitiesState {
    public data object Loading : OpportunitiesState

    public data class Unavailable(val message: String) : OpportunitiesState

    public data class Loaded(val week: Int, val rows: List<OpportunityRow>) : OpportunitiesState
}

/** Who has a player in the user's league. */
public sealed interface Owner {
    public data object FreeAgent : Owner

    public data object Yours : Owner

    public data class Other(val team: String) : Owner
}

/** [playerId]'s owner in the synced league; null when no league is synced (so ownership is unknown). */
public fun ownerOf(playerId: String, league: LeagueRostered?, mine: Set<String>): Owner? = when {
    league == null -> null
    playerId in mine -> Owner.Yours
    else -> league.owners[playerId]?.let(Owner::Other) ?: if (playerId in league.playerIds) Owner.Other("another team") else Owner.FreeAgent
}

/** ESPN's code as the injury report words it. */
internal fun statusWord(abbr: String): String = when (abbr) {
    "D" -> "Doubtful"
    "O" -> "Out"
    "IR" -> "IR"
    "Q" -> "Questionable"
    else -> abbr
}

/** "RB1 Christian McCaffrey is Doubtful" for the first hurt starter ahead of [row]. */
internal fun injuredNote(row: OpportunityRow): String? =
    row.beneficiary.injured.firstOrNull()?.let { "${row.beneficiary.player.position}${it.rank} ${it.name} is ${statusWord(it.abbr)}" }

/** Loads the replacements whose starters are hurt, scored with the active profile. */
public class OpportunitiesViewModel(private val repository: OpportunitiesRepository) : ViewModel() {
    private val _state = MutableStateFlow<OpportunitiesState>(OpportunitiesState.Loading)
    public val state: StateFlow<OpportunitiesState> = _state.asStateFlow()

    // A load superseded by a newer one (a profile switch mid-load) must not overwrite it.
    private var latest = 0

    public fun load(season: Int, profile: ScoringProfile) {
        val request = ++latest
        _state.value = OpportunitiesState.Loading
        viewModelScope.launch {
            val next = try {
                repository.find(season, profile).toState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OpportunitiesState.Unavailable("Couldn't load opportunities: ${e.message}.")
            }
            if (request == latest) _state.value = next
        }
    }

    public companion object {
        public fun factory(repository: OpportunitiesRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { OpportunitiesViewModel(repository) } }
    }
}

internal fun OpportunitiesResult.toState(): OpportunitiesState =
    if (message != null && rows.isEmpty()) OpportunitiesState.Unavailable(message!!) else OpportunitiesState.Loaded(week, rows)
