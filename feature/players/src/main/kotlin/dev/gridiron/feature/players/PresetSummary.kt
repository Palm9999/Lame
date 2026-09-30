package dev.gridiron.feature.players

import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.datastore.GridPreset
import dev.gridiron.core.datastore.PresetWeeks

/** One line for the presets sheet: "WR · FTN Receiving · last 4 wks · 2 filters". Names it no longer knows show as stored. */
internal fun presetSummary(preset: GridPreset): String {
    val position = PositionFilter.entries.firstOrNull { it.name == preset.position }
    val pack = StatPack.entries.firstOrNull { it.name == preset.packId }
    val weeks = when (val w = preset.weeks) {
        PresetWeeks.WholeSeason -> "whole season"
        is PresetWeeks.LastN -> if (w.n == 1) "last wk" else "last ${w.n} wks"
    }
    val filters = preset.filters.size
    return listOfNotNull(
        (position?.label ?: preset.position).takeIf { position != PositionFilter.ALL },
        pack?.label ?: preset.packId,
        weeks,
        when (filters) {
            0 -> null
            1 -> "1 filter"
            else -> "$filters filters"
        },
    ).joinToString(" · ")
}
