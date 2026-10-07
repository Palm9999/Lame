package dev.gridiron.feature.projections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.DynastyResult
import dev.gridiron.core.data.DynastyValue
import dev.gridiron.core.data.live.DraftPick
import dev.gridiron.core.data.live.DraftResult
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.datastore.KeeperRule
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.KeeperCandidate
import dev.gridiron.core.projections.KeeperPick
import dev.gridiron.core.projections.KeeperRow
import dev.gridiron.core.projections.Keepers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public sealed interface DynastyState {
    public data object Loading : DynastyState

    /** FantasyCalc's values and the league's draft ([draft] null when no league is synced). */
    public data class Loaded(val dynasty: DynastyResult, val draft: DraftResult?) : DynastyState
}

/** A keeper row with who he is. */
public data class KeeperListRow(val row: KeeperRow, val name: String, val position: String?)

/**
 * [team]'s players priced as keepers: each one's cost from his draft pick (by whoever drafted him, as a traded
 * player keeps his round) and worth from his redraft rank among FantasyCalc's players. [undraftedRound] is the rule's,
 * else the league's last round.
 */
public fun keeperRows(team: MyTeam, picks: List<DraftPick>, values: List<DynastyValue>, rule: KeeperRule, teams: Int, leagueRounds: Int): List<KeeperListRow> {
    val byPlayer = values.filter { it.playerId != null }.associateBy { it.playerId!! }
    val redraftRank = values.filter { it.redraftValue > 0 }.sortedByDescending { it.redraftValue }
        .mapIndexedNotNull { i, v -> v.playerId?.let { it to i + 1 } }.toMap()
    val pickOf = picks.filter { it.playerId != null }.groupBy { it.playerId!! }.mapValues { (_, p) -> p.minBy { it.round } }
    val players = team.players.filter { it.playerId != null }
    val rows = Keepers.rank(
        players.map { p ->
            val id = p.playerId!!
            KeeperCandidate(id, pickOf[id]?.let { KeeperPick(it.round, it.keeper) }, redraftRank[id], byPlayer[id]?.value)
        },
        penalty = rule.penalty,
        undraftedRound = rule.undraftedRound ?: leagueRounds,
        overrides = rule.overrides,
        teams = teams,
        keepers = rule.keepers,
    )
    val names = players.associate { it.playerId!! to it.name }
    return rows.map { KeeperListRow(it, names[it.playerId].orEmpty(), byPlayer[it.playerId]?.position) }
}

/** Loads FantasyCalc's values in the league's format and the league's draft. */
public class DynastyViewModel(
    private val dynasty: suspend (ScoringProfile) -> DynastyResult,
    private val draft: suspend (Int) -> DraftResult?,
) : ViewModel() {
    private val _state = MutableStateFlow<DynastyState>(DynastyState.Loading)
    public val state: StateFlow<DynastyState> = _state.asStateFlow()

    private var latest = 0

    public fun load(season: Int, profile: ScoringProfile) {
        val request = ++latest
        viewModelScope.launch {
            val values = dynasty(profile)
            val picks = try {
                draft(season)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DraftResult(emptyList(), e.message ?: "couldn't read the draft")
            }
            if (request == latest) _state.value = DynastyState.Loaded(values, picks)
        }
    }

    public companion object {
        public fun factory(dynasty: suspend (ScoringProfile) -> DynastyResult, draft: suspend (Int) -> DraftResult?): ViewModelProvider.Factory =
            viewModelFactory { initializer { DynastyViewModel(dynasty, draft) } }
    }
}
