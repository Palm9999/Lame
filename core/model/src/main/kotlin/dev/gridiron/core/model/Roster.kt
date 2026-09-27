package dev.gridiron.core.model

/**
 * One of the user's fantasy teams: a name and the players on it, in the order added.
 */
public data class Roster(val id: String, val name: String, val playerIds: List<String>) {
    init {
        require(id.isNotBlank()) { "a roster needs an id" }
        require(name.isNotBlank()) { "a roster needs a name" }
        require(playerIds.none { it.isBlank() }) { "a roster player needs an id" }
        require(playerIds.distinct().size == playerIds.size) { "a player is on a roster at most once" }
    }
}
