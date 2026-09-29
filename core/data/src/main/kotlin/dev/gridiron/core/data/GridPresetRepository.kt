package dev.gridiron.core.data

import dev.gridiron.core.datastore.GridPreset
import dev.gridiron.core.datastore.MAX_PRESETS
import dev.gridiron.core.datastore.MAX_PRESET_NAME
import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.datastore.PresetFilter
import dev.gridiron.core.datastore.PresetFilterKind
import dev.gridiron.core.datastore.PresetWeeks
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Another preset already has the name; [existingId] is that preset, so the caller can offer to replace it. */
public class PresetNameTaken(public val existingId: String) : IllegalStateException("a preset already has that name")

/** The user already has [MAX_PRESETS] presets. */
public class PresetLimitReached : IllegalStateException("at most $MAX_PRESETS presets")

/** A preset turned into a request, or the reason it can't be. */
public sealed interface Resolved {
    public data class Ready(val request: GridRequest) : Resolved

    public data class Unavailable(val reason: String) : Resolved
}

/**
 * The user's saved Grid views. A preset stores the view, not the season: applying
 * one follows the season and scoring the Grid has open.
 */
public class GridPresetRepository(
    private val prefs: PrefsSource,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    public val presets: Flow<ImmutableList<GridPreset>> =
        prefs.prefs.map { it.gridPresets.toImmutableList() }.distinctUntilChanged()

    /**
     * Saves [request] as [name] (trimmed) and returns it.
     *
     * @throws PresetNameTaken when another preset has the name, ignoring case
     * @throws PresetLimitReached at [MAX_PRESETS]
     */
    public suspend fun save(name: String, request: GridRequest, weeks: PresetWeeks): GridPreset {
        val trimmed = validName(name)
        var saved: GridPreset? = null
        prefs.update { p ->
            nameOwner(p.gridPresets, trimmed)?.let { throw PresetNameTaken(it.id) }
            if (p.gridPresets.size >= MAX_PRESETS) throw PresetLimitReached()
            val preset = toPreset(newId(), trimmed, request, weeks)
            saved = preset
            p.copy(gridPresets = p.gridPresets + preset)
        }
        return checkNotNull(saved)
    }

    /** Replaces preset [id]'s view with [request], keeping its id and name. A missing id is a no-op. */
    public suspend fun overwrite(id: String, request: GridRequest, weeks: PresetWeeks) {
        prefs.update { p ->
            p.copy(gridPresets = p.gridPresets.map { if (it.id == id) toPreset(it.id, it.name, request, weeks) else it })
        }
    }

    /** @throws PresetNameTaken when another preset has the name, ignoring case */
    public suspend fun rename(id: String, name: String) {
        val trimmed = validName(name)
        prefs.update { p ->
            nameOwner(p.gridPresets, trimmed)?.takeIf { it.id != id }?.let { throw PresetNameTaken(it.id) }
            p.copy(gridPresets = p.gridPresets.map { if (it.id == id) it.copy(name = trimmed) else it })
        }
    }

    public suspend fun delete(id: String) {
        prefs.update { p -> p.copy(gridPresets = p.gridPresets.filterNot { it.id == id }) }
    }

    /** Puts a deleted [preset] back at the end (undo); does nothing if its id or name is taken or the list is full. */
    public suspend fun restore(preset: GridPreset) {
        prefs.update { p ->
            val room = p.gridPresets.size < MAX_PRESETS
            val free = p.gridPresets.none { it.id == preset.id } && nameOwner(p.gridPresets, preset.name) == null
            if (room && free) p.copy(gridPresets = p.gridPresets + preset) else p
        }
    }

    /**
     * The request [preset] describes on top of [base], which supplies the season,
     * scoring profile and roster. The name search is cleared.
     */
    public fun resolve(preset: GridPreset, base: GridRequest): Resolved {
        val pack = StatPack.entries.firstOrNull { it.name == preset.packId } ?: return Resolved.Unavailable("Its stat pack is gone.")
        val sort = StatColumn.entries.firstOrNull { it.name == preset.sort } ?: return Resolved.Unavailable("Its sort column is gone.")
        if (sort !in pack.columns) return Resolved.Unavailable("Its sort column isn't in ${pack.label}.")
        val direction = Direction.entries.firstOrNull { it.name == preset.direction } ?: return Resolved.Unavailable("Its sort direction is unknown.")
        val positions = PositionFilter.entries.firstOrNull { it.name == preset.position } ?: return Resolved.Unavailable("Its position is gone.")
        if (pack !in positions.packs) return Resolved.Unavailable("${pack.label} isn't offered for ${positions.label}.")
        val filters = preset.filters.map { f ->
            val column = StatColumn.entries.firstOrNull { it.name == f.column } ?: return Resolved.Unavailable("A filter's stat is gone.")
            Filter(column, condition(f))
        }
        val request = try {
            base.copy(
                weeks = weeksFor(preset.weeks, base.season),
                pack = pack,
                positions = positions,
                sort = sort,
                direction = direction,
                perGame = preset.perGame,
                name = "",
                teams = preset.teams,
                minSnapShare = preset.minSnapShare,
                filters = filters,
            )
        } catch (e: IllegalArgumentException) {
            return Resolved.Unavailable(e.message ?: "It no longer fits the Grid.")
        }
        return Resolved.Ready(request)
    }

    /** The weeks rule that best describes [request]'s range, for the save dialog's default. */
    public fun weeksRule(request: GridRequest): PresetWeeks {
        val season = request.season.defaultWeeks
        return when {
            request.weeks == season -> PresetWeeks.WholeSeason
            request.weeks.last == season.last -> PresetWeeks.LastN(request.weeks.size)
            else -> PresetWeeks.WholeSeason
        }
    }

    private fun weeksFor(rule: PresetWeeks, season: SeasonInfo): WeekRange {
        val whole = season.defaultWeeks
        return when (rule) {
            PresetWeeks.WholeSeason -> whole
            is PresetWeeks.LastN -> WeekRange(maxOf(1, whole.last - rule.n + 1), whole.last)
        }
    }

    private fun validName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "a preset needs a name" }
        require(trimmed.length <= MAX_PRESET_NAME) { "a preset name is at most $MAX_PRESET_NAME characters" }
        return trimmed
    }

    private fun nameOwner(list: List<GridPreset>, name: String): GridPreset? = list.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun toPreset(id: String, name: String, request: GridRequest, weeks: PresetWeeks) = GridPreset(
        id = id,
        name = name,
        packId = request.pack.name,
        sort = request.sort.name,
        direction = request.direction.name,
        position = request.positions.name,
        perGame = request.perGame,
        teams = request.teams,
        minSnapShare = request.minSnapShare,
        filters = request.filters.map { presetFilter(it) },
        weeks = weeks,
    )

    private fun presetFilter(f: Filter): PresetFilter {
        val name = f.column.name
        return when (val c = f.condition) {
            is Condition.AtLeast -> PresetFilter(name, PresetFilterKind.AT_LEAST, c.value)
            is Condition.AtMost -> PresetFilter(name, PresetFilterKind.AT_MOST, c.value)
            is Condition.GreaterThan -> PresetFilter(name, PresetFilterKind.GREATER_THAN, c.value)
            is Condition.LessThan -> PresetFilter(name, PresetFilterKind.LESS_THAN, c.value)
            is Condition.Between -> PresetFilter(name, PresetFilterKind.BETWEEN, c.min, c.max)
        }
    }

    private fun condition(f: PresetFilter): Condition = when (f.kind) {
        PresetFilterKind.AT_LEAST -> Condition.AtLeast(f.a)
        PresetFilterKind.AT_MOST -> Condition.AtMost(f.a)
        PresetFilterKind.GREATER_THAN -> Condition.GreaterThan(f.a)
        PresetFilterKind.LESS_THAN -> Condition.LessThan(f.a)
        PresetFilterKind.BETWEEN -> Condition.Between(f.a, checkNotNull(f.b))
    }
}
