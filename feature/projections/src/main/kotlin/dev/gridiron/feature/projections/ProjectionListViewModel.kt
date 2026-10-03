package dev.gridiron.feature.projections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.ListedProjection
import dev.gridiron.core.projections.projectPoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

public data class ProjectionRow(
    val playerId: String,
    val name: String,
    val position: String,
    val team: String?,
    val points: Double,
    val floor: Double,
    val ceiling: Double,
    /** ESPN lists him Out or on IR: this week's points show as zero. */
    val out: Boolean = false,
)

public enum class PositionTab(public val label: String, public val codes: Set<String>) {
    QB("QB", setOf("QB")),
    RB("RB", setOf("RB")),
    WR("WR", setOf("WR")),
    TE("TE", setOf("TE")),
    FLEX("FLEX", setOf("RB", "WR", "TE")),
    K("K", setOf("K")),
    DST("D/ST", setOf("DST")),
}

public sealed interface ProjectionListState {
    public data object Loading : ProjectionListState

    public data class Unavailable(val message: String) : ProjectionListState

    public data class Loaded(
        val week: Int,
        val builtAt: Instant?,
        val weekRows: List<ProjectionRow>,
        val rosRows: List<ProjectionRow>,
    ) : ProjectionListState
}

private val OUT = setOf("O", "IR")

/** The rows a tab shows, best first. In [week] mode an Out or IR player scores zero; rest of season keeps his projection. */
public fun visibleRows(rows: List<ProjectionRow>, tab: PositionTab, badges: Map<String, String>, week: Boolean): List<ProjectionRow> =
    rows.filter { it.position in tab.codes }
        .map { row -> if (week) outAdjusted(row, badges) else row }
        .sortedByDescending { it.points }

/** [row] scored as zero this week when ESPN lists him Out or on IR. */
internal fun outAdjusted(row: ProjectionRow, badges: Map<String, String>): ProjectionRow =
    if (badges[row.playerId] in OUT) row.copy(points = 0.0, floor = 0.0, ceiling = 0.0, out = true) else row

private val BUILT = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

/** "Projections for week 4 · built Tue 7:02 AM". */
public fun statusLine(week: Int, builtAt: Instant?, zone: ZoneId = ZoneId.systemDefault()): String {
    val built = builtAt?.let { " · built " + BUILT.format(it.atZone(zone)) }.orEmpty()
    return "Projections for week $week$built"
}

/** A list scores hundreds of players, so it simulates each with fewer draws than the single-player waterfall. */
private const val LIST_DRAWS = 2_000

internal fun toRows(listed: List<ListedProjection>, profile: ScoringProfile): List<ProjectionRow> = listed.mapNotNull { p ->
    val position = p.position ?: return@mapNotNull null
    val points = projectPoints(p.components, profile, Position.fromCode(position), draws = LIST_DRAWS)
    ProjectionRow(p.playerId, p.name, position, p.team, points.points, points.floor, points.ceiling)
}

/** Loads the upcoming week's and rest of season's projections and scores them with the active profile. */
public class ProjectionListViewModel(
    private val repository: ProjectionsRepository,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<ProjectionListState>(ProjectionListState.Loading)
    public val state: StateFlow<ProjectionListState> = _state.asStateFlow()

    // A load superseded by a newer one (a profile switch mid-load) must not overwrite it.
    private var latest = 0

    public fun load(season: Int, profile: ScoringProfile) {
        val request = ++latest
        _state.value = ProjectionListState.Loading
        viewModelScope.launch {
            val next = try {
                build(season, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProjectionListState.Unavailable("Couldn't load projections: ${e.message}.")
            }
            if (request == latest) _state.value = next
        }
    }

    private suspend fun build(season: Int, profile: ScoringProfile): ProjectionListState {
        val status = repository.status()
        val week = status.upcoming[season]
        return when {
            status.status == null -> ProjectionListState.Unavailable("No projections yet. Refresh stats to build them.")
            status.status != "ok" -> ProjectionListState.Unavailable("Projections unavailable: ${status.status}.")
            week == null -> ProjectionListState.Unavailable("No upcoming games in $season.")
            else -> {
                val weekListed = repository.weekAll(season, week)
                val rosListed = repository.rosAll(season)
                withContext(compute) {
                    ProjectionListState.Loaded(week, status.builtAt, toRows(weekListed, profile), toRows(rosListed, profile))
                }
            }
        }
    }

    public companion object {
        public fun factory(repository: ProjectionsRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { ProjectionListViewModel(repository) } }
    }
}
