package dev.gridiron.feature.scoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ListState(
    val profiles: ImmutableList<ScoringProfile> = ScoringPresets.all.toImmutableList(),
    val activeId: String = ScoringPresets.PPR.id,
    /** Set after Duplicate: the screen opens this profile in the editor, then sends [ListEvent.EditOpened]. */
    val editRequest: String? = null,
)

internal sealed interface ListEvent {
    data class SetActive(val id: String) : ListEvent
    data class Duplicate(val id: String) : ListEvent
    data class Delete(val id: String) : ListEvent
    data object EditOpened : ListEvent
}

/**
 * Lists the presets and the user's own scoring profiles.
 *
 * [ListState.editRequest] is applied straight to [_state] (never through the
 * repository combine below), so [ListEvent.EditOpened] is visible in
 * [state]`.value` the instant it's sent, with no coroutine dispatch to wait on.
 */
internal class ScoringListViewModel(private val repository: ScoringRepository) : ViewModel() {
    private val _state = MutableStateFlow(ListState())
    val state: StateFlow<ListState> = _state

    init {
        viewModelScope.launch {
            combine(repository.profiles, repository.active) { profiles, active -> profiles to active }
                .collect { (profiles, active) -> _state.update { it.copy(profiles = profiles, activeId = active.id) } }
        }
    }

    fun onEvent(event: ListEvent) {
        when (event) {
            is ListEvent.SetActive -> viewModelScope.launch { repository.setActive(event.id) }
            is ListEvent.Duplicate -> viewModelScope.launch {
                val source = _state.value.profiles.first { it.id == event.id }
                val created = repository.duplicate(event.id, "${source.name} copy")
                _state.update { it.copy(editRequest = created.id) }
            }
            is ListEvent.Delete -> viewModelScope.launch { repository.delete(event.id) }
            ListEvent.EditOpened -> _state.update { it.copy(editRequest = null) }
        }
    }

    companion object {
        fun factory(repository: ScoringRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScoringListViewModel(repository) }
        }
    }
}
