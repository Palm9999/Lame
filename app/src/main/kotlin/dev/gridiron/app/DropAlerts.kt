package dev.gridiron.app

import dev.gridiron.core.data.live.ActivityItem
import dev.gridiron.core.projections.LineupCandidate
import dev.gridiron.core.projections.Lineups
import dev.gridiron.core.projections.Pickup
import java.io.File

/** A player dropped in the user's league, now a free agent, and what adding him does to their rest of season. */
data class DropAlert(val pickup: Pickup, val name: String)

/**
 * Drops to free agency in the user's league since the last check: each drop's move is remembered in [seen] (one id a
 * line) so it alerts once. The first check only remembers what's there, so turning the alert on doesn't replay the
 * season.
 */
class DropAlertChecker(private val seen: File) {
    /**
     * The new drops among [items] (not the user's own, [myTeamId]) of players still free ([rostered] is everyone on a
     * team now) that would lift [roster]'s rest of season in [slots] by [MIN_GAIN] points or more, best first.
     * [rosPoints] is each player's rest-of-season points under the user's scoring, by app id.
     */
    @Synchronized
    fun check(
        items: List<ActivityItem>,
        myTeamId: Int?,
        rostered: Set<String>,
        slots: Map<String, Int>,
        roster: List<LineupCandidate>,
        rosPoints: Map<String, LineupCandidate>,
    ): List<DropAlert> {
        val drops = items.flatMap { item -> item.moves.filter { it.toTeamId == 0 && it.fromTeamId != 0 }.map { "${item.id}:${it.espnId}" to it } }
        val known = runCatching { seen.readLines().toSet() }.getOrNull()
        runCatching { seen.writeText((known.orEmpty() + drops.map { it.first }).toList().takeLast(KEEP).joinToString("\n")) }
        if (known == null) return emptyList()
        val fresh = drops.filter { (key, move) -> key !in known && move.fromTeamId != myTeamId }
            .mapNotNull { (_, move) -> move.playerId?.takeIf { it !in rostered }?.let { id -> rosPoints[id]?.let { it to (move.name ?: id) } } }
            .distinctBy { it.first.playerId }
        if (fresh.isEmpty()) return emptyList()
        val names = fresh.associate { it.first.playerId to it.second }
        return Lineups.pickups(slots, roster, fresh.map { it.first }, limit = 3)
            .filter { it.gain >= MIN_GAIN }
            .map { DropAlert(it, names.getValue(it.add.playerId)) }
    }

    private companion object {
        /** A rest-of-season gain worth a notification: about a point a week over the season's second half. */
        const val MIN_GAIN = 8.0

        /** Plenty for a season's drops. */
        const val KEEP = 1_000
    }
}
