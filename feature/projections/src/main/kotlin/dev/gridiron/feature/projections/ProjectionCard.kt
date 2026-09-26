package dev.gridiron.feature.projections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.GameLine
import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.projections.RosProjectionsRequest
import dev.gridiron.core.projections.projectPoints
import dev.gridiron.core.projections.score
import dev.gridiron.core.statquery.Component
import java.util.Locale
import kotlin.math.abs

/** What the Player page's "This week" card shows. */
public data class ProjectionCard(
    val season: Int,
    val week: Int,
    /** "vs DAL" or "@ DAL". */
    val matchup: String?,
    /** "KC −3.5 · O/U 47.5", or null until the line is posted. */
    val line: String?,
    val points: Double,
    val floor: Double,
    val ceiling: Double,
    val rosPoints: Double?,
    val rosPerGame: Double?,
    /** ESPN lists him Out or on IR. */
    val out: Boolean,
    /** His team has no game this week; only rest of season counts. */
    val bye: Boolean = false,
)

private val OUT_ABBRS = setOf("O", "IR")

/**
 * The card for [playerId] in the latest season's upcoming week, scored with
 * [profile]; null when the forecast has nothing for him (it failed, it's the
 * off-season, or he has no projection this week). On his team's bye the card
 * carries only rest of season.
 */
public suspend fun loadProjectionCard(
    repository: ProjectionsRepository,
    playerId: String,
    team: String?,
    profile: ScoringProfile,
    position: Position?,
    injuryAbbr: String?,
): ProjectionCard? {
    val status = repository.status()
    if (status.status != "ok") return null
    val (season, week) = status.upcoming.maxByOrNull { it.key }?.toPair() ?: return null
    val final = repository.projections(ProjectionsRequest(setOf(playerId), season, week)).firstOrNull()?.final.orEmpty()
    val game = team?.let { repository.game(season, week, it) }
    val ros = repository.rosProjections(RosProjectionsRequest(setOf(playerId), season)).firstOrNull()
    val rosPoints = ros?.let { r -> score(r.components.associate { Component(it.metricId) to it.mean }, profile, position) }
    val gamesLeft = team?.let { repository.remainingGames(season, week, it) } ?: 0
    val rosPerGame = rosPoints?.takeIf { gamesLeft > 0 }?.let { it / gamesLeft }
    if (final.isEmpty()) {
        if (team == null || game != null || rosPoints == null) return null
        return ProjectionCard(season, week, null, null, 0.0, 0.0, 0.0, rosPoints, rosPerGame, out = false, bye = true)
    }
    val out = injuryAbbr in OUT_ABBRS
    val points = projectPoints(final, profile, position)
    return ProjectionCard(
        season = season,
        week = week,
        matchup = game?.let(::matchupText),
        line = game?.let(::lineText),
        points = if (out) 0.0 else points.points,
        floor = if (out) 0.0 else points.floor,
        ceiling = if (out) 0.0 else points.ceiling,
        rosPoints = rosPoints,
        rosPerGame = rosPerGame,
        out = out,
    )
}

public fun matchupText(line: GameLine): String = if (line.home) "vs ${line.opponent}" else "@ ${line.opponent}"

/** "KC −3.5 · O/U 47.5" from [GameLine.team]'s side; a favorite gets the minus sign. */
public fun lineText(line: GameLine): String? {
    val parts = buildList {
        line.favoredBy?.let { f -> add(if (f == 0.0) "Pick'em" else "${line.team} ${if (f > 0) "−" else "+"}${trim(abs(f))}") }
        line.total?.let { add("O/U ${trim(it)}") }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

private fun trim(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

private fun onePlace(value: Double): String = String.format(Locale.US, "%.1f", value)

/** The Player page's projection card; tapping it opens the waterfall. */
@Composable
public fun ThisWeekCard(card: ProjectionCard, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clickable(enabled = !card.bye, onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            listOfNotNull("Week ${card.week}", card.matchup, card.line).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (card.bye) {
            Text("Bye this week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        } else if (card.out) {
            Text("Out this week", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        } else {
            Text("${onePlace(card.points)} pts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Floor ${onePlace(card.floor)} · Ceiling ${onePlace(card.ceiling)}", style = MaterialTheme.typography.bodySmall)
        }
        card.rosPoints?.let { ros ->
            val perGame = card.rosPerGame?.let { " (${onePlace(it)} per game)" }.orEmpty()
            Text("Rest of season ${onePlace(ros)} pts$perGame", style = MaterialTheme.typography.bodySmall)
        }
        if (!card.bye) Text("See why →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}
