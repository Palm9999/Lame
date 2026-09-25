package dev.gridiron.core.model

/**
 * One column on the Compare screen: a player over one season's week range.
 * The same player may fill several slots with different seasons or ranges,
 * which is how a player is compared with himself.
 */
public data class CompareSlot(val playerId: String, val season: Int, val weeks: WeekRange) {
    init {
        require(playerId.isNotBlank()) { "a compare slot needs a player" }
    }
}
