package dev.gridiron.feature.projections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.SeasonGame
import dev.gridiron.core.designsystem.EmptyState
import dev.gridiron.core.designsystem.LoadingRows
import dev.gridiron.core.designsystem.ScreenBar
import dev.gridiron.core.designsystem.TeamChip
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * One game of the week: the betting line's implied points for each side (the total split by the spread) beside the
 * season so far's ([formPoints]). Null where a number is missing.
 */
internal data class LineRow(
    val away: String,
    val home: String,
    val vegasAway: Double?,
    val vegasHome: Double?,
    val formAway: Double?,
    val formHome: Double?,
) {
    val vegasTotal: Double? get() = if (vegasAway != null && vegasHome != null) vegasAway + vegasHome else null
    val formTotal: Double? get() = if (formAway != null && formHome != null) formAway + formHome else null

    /** The season's total against the line's: positive leans over. */
    val totalGap: Double? get() = formTotal?.let { m -> vegasTotal?.let { m - it } }

    /** The season's home margin against the line's: positive leans home. */
    val marginGap: Double? get() =
        if (formAway != null && formHome != null && vegasAway != null && vegasHome != null) (formHome - formAway) - (vegasHome - vegasAway) else null
}

/**
 * [week]'s games from [games] (a season's, scores and lines), each side's points two ways: the line's ((total + its
 * margin) / 2; nflverse's spread is the home side's) and the season's before [week] (it scores as it has, against what
 * the other side has allowed, over the league's average: the forecast's own matchup step). Biggest disagreement first.
 */
internal fun lineRows(games: List<SeasonGame>, week: Int): List<LineRow> {
    val played = games.filter { it.week < week && it.homeScore != null && it.awayScore != null }
    val scored = mutableMapOf<String, MutableList<Int>>()
    val allowed = mutableMapOf<String, MutableList<Int>>()
    for (g in played) {
        val home = g.homeScore!!
        val away = g.awayScore!!
        scored.getOrPut(g.home) { mutableListOf() }.add(home)
        scored.getOrPut(g.away) { mutableListOf() }.add(away)
        allowed.getOrPut(g.home) { mutableListOf() }.add(away)
        allowed.getOrPut(g.away) { mutableListOf() }.add(home)
    }
    val league = played.flatMap { listOf(it.homeScore!!, it.awayScore!!) }.average().takeIf { !it.isNaN() && it > 0.0 }
    fun form(team: String, opponent: String): Double? {
        val s = scored[team]?.average() ?: return null
        val a = allowed[opponent]?.average() ?: return null
        return league?.let { s * a / it }
    }
    return games.filter { it.week == week }.map { g ->
        val homeImplied = g.total?.let { t -> g.spread?.let { (t + it) / 2 } }
        LineRow(g.away, g.home, homeImplied?.let { g.total!! - it }, homeImplied, form(g.away, g.home), form(g.home, g.away))
    }.sortedByDescending { r -> maxOf(abs(r.totalGap ?: 0.0), abs(r.marginGap ?: 0.0)) }
}

/** The upcoming week's games: the line's implied scores beside the season's, the biggest differences first. */
@Composable
public fun LinesRoute(repository: ProjectionsRepository, onBack: () -> Unit, dataVersion: Flow<Long> = flowOf(0L)) {
    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)
    var rows by remember { mutableStateOf<List<LineRow>?>(null) }
    var week by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        try {
            val (season, w) = repository.status().upcoming.maxByOrNull { it.key }?.toPair() ?: run {
                rows = emptyList()
                return@LaunchedEffect
            }
            week = w
            rows = lineRows(repository.seasonGames(season), w)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    LinesScreen(rows, week, failed, onBack)
}

@Composable
internal fun LinesScreen(rows: List<LineRow>?, week: Int?, failed: Boolean, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar(week?.let { "Game lines · week $it" } ?: "Game lines", onBack)
            when {
                failed -> EmptyState("Couldn't read this week's games. Refresh stats and try again.")
                rows == null -> LoadingRows()
                rows.isEmpty() -> EmptyState("No projected week yet. Refresh stats.")
                else -> LazyColumn(Modifier.testTag("lines")) {
                    item {
                        Text(
                            "Each side's points: the betting line's (its total split by the spread) beside the season's (what it has " +
                                "scored against what the other side has allowed, over the league's average). The app's projections use " +
                                "the line, so a gap of 3 or more says the season so far disagrees with the market, not the app.",
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(rows, key = { "${it.away}@${it.home}" }) { r ->
                        LineGame(r)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LineGame(r: LineRow) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("line:${r.away}@${r.home}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("", Modifier.weight(1f))
            Text("Line", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Season", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for ((team, vegas, model) in listOf(Triple(r.away, r.vegasAway, r.formAway), Triple("@ ${r.home}", r.vegasHome, r.formHome))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (team.startsWith("@ ")) Text("@ ", style = MaterialTheme.typography.bodyMedium)
                    TeamChip(team.removePrefix("@ "))
                }
                Text(vegas?.let(::one) ?: "–", Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
                Text(model?.let(::one) ?: "–", Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        leanText(r)?.let {
            Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "Season leans over by 3.5 · leans KC by 3.0", naming only gaps of [LEAN] points or more. */
internal fun leanText(r: LineRow): String? = listOfNotNull(
    r.totalGap?.takeIf { abs(it) >= LEAN }?.let { "Season leans ${if (it > 0) "over" else "under"} by ${one(abs(it))}" },
    r.marginGap?.takeIf { abs(it) >= LEAN }?.let { "leans ${if (it > 0) r.home else r.away} by ${one(abs(it))}" },
).joinToString(" · ").ifEmpty { null }

/** A gap the season and the line disagree by before it's called a lean: a field goal. */
private const val LEAN = 3.0

private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)
