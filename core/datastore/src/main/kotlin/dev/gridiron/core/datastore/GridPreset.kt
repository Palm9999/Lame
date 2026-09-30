package dev.gridiron.core.datastore

import dev.gridiron.core.model.WeekRange

/** Most presets the user can keep. */
public const val MAX_PRESETS: Int = 30

/** Longest preset name, in characters. */
public const val MAX_PRESET_NAME: Int = 40

/**
 * A saved Grid view. Everything is a plain string or number (enum names, stored
 * units) because this module sees only `:core:model`; `:core:data` turns one
 * into a request against the season the user has open.
 *
 * @property sort The [packId] pack's sort column, by enum name.
 * @property position The position chip, by enum name.
 * @property weeks A rule, not week numbers, so the preset follows the season.
 */
public data class GridPreset(
    val id: String,
    val name: String,
    val packId: String,
    val sort: String,
    val direction: String,
    val position: String,
    val perGame: Boolean,
    val teams: Set<String>,
    val minSnapShare: Double?,
    val filters: List<PresetFilter>,
    val weeks: PresetWeeks,
) {
    init {
        require(id.isNotBlank()) { "a preset needs an id" }
        require(name == name.trim() && name.isNotEmpty()) { "a preset needs a trimmed, non-blank name" }
        require(name.length <= MAX_PRESET_NAME) { "a preset name is at most $MAX_PRESET_NAME characters" }
        require(minSnapShare == null || minSnapShare.isFinite()) { "snap share must be finite" }
    }
}

public enum class PresetFilterKind { AT_LEAST, AT_MOST, GREATER_THAN, LESS_THAN, BETWEEN }

/** One advanced filter: [a] is the value, or the minimum for [PresetFilterKind.BETWEEN], whose maximum is [b]. */
public data class PresetFilter(val column: String, val kind: PresetFilterKind, val a: Double, val b: Double? = null) {
    init {
        require(column.isNotBlank()) { "a filter needs a column" }
        require(a.isFinite()) { "filter value must be finite" }
        if (kind == PresetFilterKind.BETWEEN) {
            require(b != null && b.isFinite() && a <= b) { "a between filter needs a maximum at or above its minimum" }
        }
    }
}

/** Which weeks a preset shows once applied. */
public sealed interface PresetWeeks {
    /** The season's played regular-season weeks. */
    public data object WholeSeason : PresetWeeks

    /** The last [n] played weeks. */
    public data class LastN(val n: Int) : PresetWeeks {
        init {
            require(n in 1..WeekRange.MAX_WEEK) { "last $n weeks outside 1..${WeekRange.MAX_WEEK}" }
        }
    }
}
