package dev.gridiron.core.data

import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.ImmutableList

/** What the Compare screen is asking for. */
public data class CompareRequest(val slots: List<CompareSlot>, val scoring: ScoringProfile, val perGame: Boolean)

/** Everything the Compare screen shows, built from one request. */
public data class ComparePage(
    val request: CompareRequest,
    val slots: ImmutableList<SlotHeader>,
    val groups: ImmutableList<CompareGroupUi>,
    val radar: RadarUi?,
    val scatter: ScatterUi?,
) {
    /** Indexes of the slots with a row to chart; the rest are excluded from charts. */
    public val chartedSlots: List<Int> get() = slots.indices.filter { slots[it].status.charted }
}

/** One tray slot's identity and how it fared. */
public data class SlotHeader(val slot: CompareSlot, val name: String, val detail: String, val status: SlotStatus, val position: Position?)

/** Why a slot does or doesn't have a full ranked row. */
public enum class SlotStatus {
    OK,
    SMALL_SAMPLE,
    NO_GAMES,
    NO_SEASON,
    MISSING,
    ;

    /** Whether the slot has values to show; a slot without them is excluded from charts. */
    public val charted: Boolean get() = this == OK || this == SMALL_SAMPLE
}

/** One group of rows (Opportunity, Efficiency, Scoring, Context), with a composite score per slot. */
public data class CompareGroupUi(val group: CompareGroup, val composite: ImmutableList<Float?>, val rows: ImmutableList<CompareRowUi>)

/** One stat row across every slot. */
public data class CompareRowUi(
    val column: StatColumn,
    val label: String,
    val info: MetricInfo?,
    val cells: ImmutableList<CompareCellUi>,
    val spread: Float?,
    val best: Int?,
    val diff: String?,
)

/** One slot's value for one row. */
public data class CompareCellUi(val text: String, val value: Double?, val percentile: Float?)

/** Axis labels and one polygon of values per slot. */
public data class RadarUi(val axes: ImmutableList<String>, val values: ImmutableList<ImmutableList<Float?>>)

/** Fantasy points vs. expected fantasy points for a position's population, with the compared slots picked out. */
public data class ScatterUi(
    val position: Position,
    val season: Int,
    val weeks: WeekRange,
    val population: ImmutableList<ScatterPointUi>,
    val slots: ImmutableList<ScatterPointUi?>,
)

/** One point on the scatter: a player's actual vs. expected fantasy points per game. */
public data class ScatterPointUi(val playerId: String, val name: String, val xfpPerGame: Double, val fpPerGame: Double)
