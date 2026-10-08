package dev.gridiron.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.GameDetail
import dev.gridiron.core.data.GamePlayer
import dev.gridiron.core.data.GameState
import dev.gridiron.core.data.ScoreGame
import dev.gridiron.core.data.ScoresRepository
import dev.gridiron.core.data.ScoresWeek
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.designsystem.LoadingRows
import dev.gridiron.core.designsystem.PullToRefresh
import dev.gridiron.core.designsystem.ScreenBar
import dev.gridiron.core.designsystem.playerClick
import dev.gridiron.core.model.Position
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A week's name: "Week 4", and the four playoff rounds nflverse numbers 19 to 22. */
internal fun weekLabel(week: Int): String = when (week) {
    19 -> "Wild Card"
    20 -> "Divisional"
    21 -> "Conference"
    22 -> "Super Bowl"
    else -> "Week $week"
}

private val KICKOFF = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

/** "Sun 1:00 PM" in the phone's time zone. */
internal fun formatKickoff(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String = KICKOFF.format(at.atZone(zone))

/** A game's right-hand status: the clock while live, "Final" or "Final/OT", else the kickoff time. */
internal fun statusText(game: ScoreGame, zone: ZoneId = ZoneId.systemDefault()): String = when (game.state) {
    GameState.LIVE -> game.detail ?: "Live"
    GameState.FINAL -> game.detail ?: "Final"
    GameState.SCHEDULED -> game.kickoff?.let { formatKickoff(it, zone) } ?: "Scheduled"
}

/** The season's weeks one at a time, opening on the week still to play; a game opens its players. */
@Composable
fun ScoresRoute(
    season: Int,
    scores: ScoresRepository,
    onGame: (week: Int, home: String, away: String) -> Unit,
    onBack: (() -> Unit)?,
    /** Bumped when a refresh swaps in new stats: the week loads again. */
    dataVersion: Flow<Long> = flowOf(0L),
) {
    val stats by dataVersion.collectAsState(initial = 0L)
    var weeks by remember { mutableStateOf<List<Int>?>(null) }
    var week by rememberSaveable { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<ScoresWeek?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(season, stats) {
        weeks = scores.weeks(season)
        if (week == 0) week = scores.currentWeek(season) ?: 0
    }
    LaunchedEffect(season, week, stats, reload) {
        if (week == 0) return@LaunchedEffect
        failed = false
        page = null
        // A week with a live game keeps its scores fresh while the screen is open.
        while (true) {
            val loaded = try {
                scores.week(season, week)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
                return@LaunchedEffect
            }
            page = loaded
            if (loaded.games.none { it.state == GameState.LIVE }) return@LaunchedEffect
            delay(LIVE_REFRESH_MS)
        }
    }
    ScoresScreen(season, weeks, week, page, failed, onWeek = { week = it }, onRefresh = { reload++ }, onGame = onGame, onBack = onBack)
}

private const val LIVE_REFRESH_MS = 60_000L

@Composable
fun ScoresScreen(
    season: Int,
    weeks: List<Int>?,
    week: Int,
    page: ScoresWeek?,
    failed: Boolean,
    onWeek: (Int) -> Unit,
    onRefresh: () -> Unit,
    onGame: (week: Int, home: String, away: String) -> Unit,
    onBack: (() -> Unit)?,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar("Scores · $season", onBack) {
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
            if (weeks != null && weeks.isNotEmpty() && week != 0) WeekPicker(weeks, week, onWeek)
            page?.liveError?.let { LiveCaption(null, it) }
            when {
                failed -> Message("Couldn't load this week.", onRefresh)
                weeks != null && weeks.isEmpty() -> Message("No games for $season in the stats yet.")
                page == null -> LoadingRows()
                page.games.isEmpty() -> Message("No games this week.")
                // The week reloads to its skeleton, so the pull's own spinner needn't stay.
                else -> PullToRefresh(refreshing = false, onRefresh) {
                    LazyColumn(Modifier.testTag("scores-list")) {
                        items(page.games, key = { "${it.home}-${it.away}" }) { game ->
                            GameRow(game) { onGame(page.week, game.home, game.away) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        if (page.byes.isNotEmpty()) {
                            item(key = "byes") {
                                Text(
                                    "Bye: ${page.byes.joinToString(", ")}",
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WeekPicker(weeks: List<Int>, week: Int, onWeek: (Int) -> Unit) {
    val index = weeks.indexOf(week)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onWeek(weeks[index - 1]) }, enabled = index > 0) { Text("‹") }
        Text(weekLabel(week), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        TextButton(onClick = { onWeek(weeks[index + 1]) }, enabled = index in 0 until weeks.lastIndex) { Text("›") }
    }
}

@Composable
private fun GameRow(game: ScoreGame, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        TeamLine(game.away, game.awayScore, won = game.winner == game.away, lost = game.winner == game.home)
        TeamLine(game.home, game.homeScore, won = game.winner == game.home, lost = game.winner == game.away)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                statusText(game),
                style = MaterialTheme.typography.bodySmall,
                color = if (game.state == GameState.LIVE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(game.spreadText, game.total?.let { "O/U $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TeamLine(team: String, score: Int?, won: Boolean, lost: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            team,
            Modifier.weight(1f),
            fontWeight = if (won) FontWeight.Bold else FontWeight.Normal,
            color = if (lost) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            score?.toString() ?: "",
            fontWeight = if (won) FontWeight.Bold else FontWeight.Normal,
            color = if (lost) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One game: its score line, then each team's players with their fantasy points under the active profile. */
@Composable
fun GameRoute(
    season: Int,
    week: Int,
    home: String,
    away: String,
    scores: ScoresRepository,
    scoring: ScoringRepository,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    dataVersion: Flow<Long> = flowOf(0L),
) {
    val stats by dataVersion.collectAsState(initial = 0L)
    val profile by scoring.active.collectAsState(initial = null)
    var game by remember { mutableStateOf<ScoreGame?>(null) }
    var detail by remember { mutableStateOf<GameDetail?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(season, week, home, away, stats, profile) {
        val p = profile ?: return@LaunchedEffect
        failed = false
        try {
            game = scores.week(season, week).games.firstOrNull { it.home == home && it.away == away }
            detail = scores.detail(season, week, home, away, p)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    GameScreen(season, week, home, away, game, detail, profile?.name, failed, onPlayer, onBack)
}

@Composable
fun GameScreen(
    season: Int,
    week: Int,
    home: String,
    away: String,
    game: ScoreGame?,
    detail: GameDetail?,
    profileName: String?,
    failed: Boolean,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar("$away @ $home · ${weekLabel(week)} $season", onBack)
            if (game != null) {
                Text(
                    listOfNotNull(
                        game.awayScore?.let { "$away $it – ${game.homeScore} $home" },
                        statusText(game),
                        game.spreadText,
                    ).joinToString(" · "),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when {
                failed -> Message("Couldn't load this game.")
                detail == null -> LoadingRows()
                detail.home.isEmpty() && detail.away.isEmpty() -> Message("No player stats for this game yet.")
                else -> LazyColumn(Modifier.testTag("game-players")) {
                    teamSection(away, detail.away, profileName, onPlayer)
                    teamSection(home, detail.home, profileName, onPlayer)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.teamSection(
    team: String,
    players: List<GamePlayer>,
    profileName: String?,
    onPlayer: (String) -> Unit,
) {
    item(key = "team:$team") {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(team, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(profileName?.let { "Fantasy pts · $it" } ?: "Fantasy pts", style = MaterialTheme.typography.labelSmall)
        }
    }
    items(players, key = { "$team:${it.playerId}" }) { p ->
        Row(Modifier.fillMaxWidth().playerClick(p.playerId, onPlayer).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.Bold)
                Text(p.position?.let(Position::label) ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(p.points?.let { "%.1f".format(Locale.US, it) } ?: "–")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
