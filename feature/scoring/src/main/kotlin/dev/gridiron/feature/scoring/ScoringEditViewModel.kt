package dev.gridiron.feature.scoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.YardageBonus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One bonus row's fields as typed, before they're parsed into a [YardageBonus]. */
internal data class BonusDraft(val key: Int, val stat: BonusStat, val min: String, val max: String, val points: String)

/** Which field an error message belongs to. */
internal sealed interface FieldKey {
    data object Name : FieldKey
    data class Weight(val rule: ScoringRule) : FieldKey
    data class Reception(val position: Position) : FieldKey
    data class BonusMin(val key: Int) : FieldKey
    data class BonusMax(val key: Int) : FieldKey
    data class BonusPoints(val key: Int) : FieldKey
}

internal sealed interface EditState {
    data object Loading : EditState
    data object NotFound : EditState

    data class Editing(
        val original: ScoringProfile,
        val name: String,
        val weights: ImmutableMap<ScoringRule, String>,
        val reception: ImmutableMap<Position, String>,
        val bonuses: ImmutableList<BonusDraft>,
        val readOnly: Boolean,
        val saved: Boolean = false,
    ) : EditState {
        private val validated: Pair<Map<FieldKey, String>, ScoringProfile?> by lazy { validate() }
        val errors: Map<FieldKey, String> get() = validated.first

        /** The profile these fields describe, or null while any field is invalid. */
        val profile: ScoringProfile? get() = validated.second
        val dirty: Boolean get() = profile != original
    }
}

internal sealed interface EditEvent {
    data class NameChanged(val name: String) : EditEvent
    data class WeightChanged(val rule: ScoringRule, val text: String) : EditEvent
    data class ReceptionChanged(val position: Position, val text: String) : EditEvent
    data object BonusAdded : EditEvent
    data class BonusChanged(val key: Int, val draft: BonusDraft) : EditEvent
    data class BonusRemoved(val key: Int) : EditEvent
    data object ResetToPreset : EditEvent
    data object Save : EditEvent
}

private const val NUMBER = "Enter a number"

private fun EditState.Editing.validate(): Pair<Map<FieldKey, String>, ScoringProfile?> {
    val errors = mutableMapOf<FieldKey, String>()
    if (name.isBlank()) errors[FieldKey.Name] = "Name the profile"
    val weights = buildMap {
        for ((rule, text) in this@validate.weights) {
            when (val r = DecimalInput.parse(text)) {
                is DecimalInput.Result.Value -> put(rule, r.value)
                DecimalInput.Result.Blank -> put(rule, 0.0)
                DecimalInput.Result.Invalid -> errors[FieldKey.Weight(rule)] = NUMBER
            }
        }
    }
    val reception = buildMap {
        for ((position, text) in this@validate.reception) {
            when (val r = DecimalInput.parse(text)) {
                is DecimalInput.Result.Value -> put(position, r.value)
                DecimalInput.Result.Blank -> Unit // blank means "use the base reception rule"
                DecimalInput.Result.Invalid -> errors[FieldKey.Reception(position)] = NUMBER
            }
        }
    }
    val bonuses = bonuses.mapNotNull { b ->
        val min = b.min.trim().toIntOrNull()?.takeIf { it in 1..1000 }
        if (min == null) errors[FieldKey.BonusMin(b.key)] = "Whole yards, 1–1000"
        val max = if (b.max.isBlank()) null else b.max.trim().toIntOrNull()
        if (b.max.isNotBlank() && max == null) errors[FieldKey.BonusMax(b.key)] = "Whole yards, or leave blank"
        if (min != null && max != null && max <= min) errors[FieldKey.BonusMax(b.key)] = "Must be above the minimum"
        val points = (DecimalInput.parse(b.points) as? DecimalInput.Result.Value)?.value
        if (points == null) errors[FieldKey.BonusPoints(b.key)] = NUMBER
        if (min == null || points == null || (max != null && max <= min)) null else YardageBonus(b.stat, min, max, points)
    }
    if (errors.isNotEmpty()) return errors to null
    return errors to original.copy(
        name = name.trim(),
        weights = weights.filterValues { it != 0.0 },
        receptionByPosition = reception,
        yardageBonuses = bonuses,
    )
}

internal fun ScoringProfile.toEditing(readOnly: Boolean): EditState.Editing = EditState.Editing(
    original = this,
    name = name,
    weights = ScoringRule.entries.associateWith { DecimalInput.format(weight(it)) }.toImmutableMap(),
    reception = ScoringProfile.RECEPTION_POSITIONS.associateWith { p -> receptionByPosition[p]?.let(DecimalInput::format).orEmpty() }
        .toImmutableMap(),
    bonuses = yardageBonuses.mapIndexed { i, b ->
        BonusDraft(i, b.stat, b.min.toString(), b.maxExclusive?.toString().orEmpty(), DecimalInput.format(b.points))
    }.toImmutableList(),
    readOnly = readOnly,
)

/** Edits one scoring profile: [profileId] can be a preset (read-only) or a user profile. */
internal class ScoringEditViewModel(private val profileId: String, private val repository: ScoringRepository) : ViewModel() {
    private val _state = MutableStateFlow<EditState>(EditState.Loading)
    val state: StateFlow<EditState> = _state

    // Bonus rows need a stable key across edits and re-orders; profile-loaded
    // rows use their index, so new rows start past any plausible list length.
    private var nextKey = 1000

    init {
        viewModelScope.launch {
            val profile = repository.profiles.first().firstOrNull { it.id == profileId }
            _state.value = profile?.toEditing(readOnly = profile.isPreset) ?: EditState.NotFound
        }
    }

    fun onEvent(event: EditEvent) {
        val s = _state.value as? EditState.Editing ?: return
        if (s.readOnly) return
        _state.value = when (event) {
            is EditEvent.NameChanged -> s.copy(name = event.name)
            is EditEvent.WeightChanged -> s.copy(weights = (s.weights + (event.rule to event.text)).toImmutableMap())
            is EditEvent.ReceptionChanged -> s.copy(reception = (s.reception + (event.position to event.text)).toImmutableMap())
            EditEvent.BonusAdded ->
                s.copy(bonuses = (s.bonuses + BonusDraft(nextKey++, BonusStat.RUSHING_YARDS, "100", "", "3")).toImmutableList())
            is EditEvent.BonusChanged -> s.copy(bonuses = s.bonuses.map { if (it.key == event.key) event.draft else it }.toImmutableList())
            is EditEvent.BonusRemoved -> s.copy(bonuses = s.bonuses.filterNot { it.key == event.key }.toImmutableList())
            EditEvent.ResetToPreset -> {
                val preset = s.original.basedOn?.let(ScoringPresets::byId) ?: return
                preset.copy(id = s.original.id, name = s.name.ifBlank { s.original.name }, basedOn = s.original.basedOn)
                    .toEditing(readOnly = false).copy(original = s.original)
            }
            EditEvent.Save -> {
                val profile = s.profile ?: return
                viewModelScope.launch {
                    repository.save(profile)
                    _state.value = profile.toEditing(readOnly = false).copy(saved = true)
                }
                return
            }
        }
    }

    companion object {
        fun factory(profileId: String, repository: ScoringRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScoringEditViewModel(profileId, repository) }
        }
    }
}
