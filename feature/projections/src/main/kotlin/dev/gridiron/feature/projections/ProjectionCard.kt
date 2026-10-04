package dev.gridiron.feature.projections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.live.DEFAULT_PLAYOFF_WEEKS
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.GameLine
import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.projections.RosProjectionsRequest
import dev.gridiron.core.projections.anytimeTd
import dev.gridiron.core.projections.projectPoints
import dev.gridiron.core.projections.projectedScore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** No projection this week though his team plays (out for now: IR, say); only rest of season counts. */
    val notThisWeek: Boolean = false,
    /** His rest-of-season place among his position's projected players (1 = most points), and how many there are. */
    val rosPlace: Int? = null,
    val rosOf: Int? = null,
    /** His points in [playoffWeeks] (the fantasy playoffs); null without weekly projections or with no game in them. */
    val playoffPoints: Double? = null,
    val playoffWeeks: List<Int> = DEFAULT_PLAYOFF_WEEKS,
    /** His chance of a rushing or receiving TD this week; null for a kicker, a D/ST or without a game. */
    val tdChance: Double? = null,
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
    /** Where the scoring and the simulation run: never the main thread. */
    compute: CoroutineDispatcher = Dispatchers.Default,
    /** The fantasy playoffs' weeks: the active league's, or 15-17. */
    playoffWeeks: List<Int> = DEFAULT_PLAYOFF_WEEKS,
): ProjectionCard? {
    val status = repository.status()
    if (status.status != "ok") return null
    val (season, week) = status.upcoming.maxByOrNull { it.key }?.toPair() ?: return null
    val final = repository.projections(ProjectionsRequest(setOf(playerId), season, week)).firstOrNull()?.final.orEmpty()
    val game = team?.let { repository.game(season, week, it) }
    val ros = repository.rosProjections(RosProjectionsRequest(setOf(playerId), season)).firstOrNull()
    val rosPoints = ros?.let { r -> withContext(compute) { projectedScore(r.components, profile, position) } }
    val gamesLeft = team?.let { repository.remainingGames(season, week, it) } ?: 0
    val rosPerGame = rosPoints?.takeIf { gamesLeft > 0 }?.let { it / gamesLeft }
    // Empty on a database built before weekly rest of season, or when he has no game in those weeks.
    val playoffPoints = repository.rosWeeks(season, playerId).firstOrNull()?.let { r ->
        withContext(compute) { r.points(profile).filterKeys { it in playoffWeeks }.values.takeIf { it.isNotEmpty() }?.sum() }
    }
    // The place is extra: failing to read it never costs the card.
    val (place, of) = if (rosPoints != null && position != null) {
        try {
            val peers = repository.rosAll(season).filter { it.position == position.code }
            withContext(compute) {
                val others = peers.filter { it.playerId != playerId }.map { projectedScore(it.components, profile, position) }
                (others.count { it > rosPoints } + 1) to (others.size + 1)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null to null
        }
    } else {
        null to null
    }
    if (final.isEmpty()) {
        if (team == null || rosPoints == null) return null
        return ProjectionCard(
            season, week, game?.let(::matchupText), game?.let(::lineText), 0.0, 0.0, 0.0, rosPoints, rosPerGame,
            out = false, bye = game == null, notThisWeek = game != null, rosPlace = place, rosOf = of,
            playoffPoints = playoffPoints, playoffWeeks = playoffWeeks,
        )
    }
    val out = injuryAbbr in OUT_ABBRS
    val points = withContext(compute) { projectPoints(final, profile, position) }
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
        rosPlace = place,
        rosOf = of,
        playoffPoints = playoffPoints,
        playoffWeeks = playoffWeeks,
        tdChance = if (out || position == Position.K || position == Position.DST) null else anytimeTd(final),
    )
}

/** "1st", "2nd", "13th". */
internal fun placeText(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$n$suffix"
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
    Column(modifier.fillMaxWidth().clickable(enabled = !card.bye && !card.notThisWeek, onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            listOfNotNull("Week ${card.week}", card.matchup, card.line).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (card.bye) {
            Text("Bye this week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        } else if (card.notThisWeek) {
            Text("Not projected this week", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        } else if (card.out) {
            Text("Out this week", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        } else {
            Text("${onePlace(card.points)} pts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull("Floor ${onePlace(card.floor)} · Ceiling ${onePlace(card.ceiling)}", card.tdChance?.let(::tdText)).joinToString(" · "),
                Modifier.testTag("card:range"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        card.rosPoints?.let { ros ->
            val perGame = card.rosPerGame?.let { " (${onePlace(it)} per game)" }.orEmpty()
            Text("Rest of season ${onePlace(ros)} pts$perGame", style = MaterialTheme.typography.bodySmall)
        }
        if (card.rosPlace != null && card.rosOf != null) {
            Text(
                "${placeText(card.rosPlace)} of ${card.rosOf} rest of season",
                Modifier.testTag("card:rosPlace"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        card.playoffPoints?.let {
            Text(
                "Playoffs (${weeksText(card.playoffWeeks)}): ${onePlace(it)} pts",
                Modifier.testTag("card:playoffs"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!card.bye && !card.notThisWeek) Text("See why →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}
