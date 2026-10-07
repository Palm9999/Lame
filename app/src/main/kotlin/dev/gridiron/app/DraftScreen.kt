package dev.gridiron.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.BoardPlayer
import dev.gridiron.core.data.DraftAdvice
import dev.gridiron.core.data.DraftBoardResult
import dev.gridiron.core.data.MockDraft
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.Lineups
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.Locale
import kotlin.random.Random

/** The picks made so far, by board key: [mine] the user's, [taken] everyone else's. Saved as "m:key" / "t:key" lines. */
data class DraftPicks(val mine: List<String> = emptyList(), val taken: Set<String> = emptySet()) {
    fun encode(): String = (mine.map { "m:$it" } + taken.map { "t:$it" }).joinToString("\n")

    /** [key] made the user's pick; again, back on the board. */
    fun toggleMine(key: String): DraftPicks = if (key in mine) copy(mine = mine - key) else copy(mine = mine + key, taken = taken - key)

    /** [key] taken by someone else; again, back on the board. */
    fun toggleTaken(key: String): DraftPicks = if (key in taken) copy(taken = taken - key) else copy(taken = taken + key, mine = mine - key)

    companion object {
        fun decode(text: String): DraftPicks {
            val lines = text.lines().filter { it.length > 2 }
            return DraftPicks(lines.filter { it.startsWith("m:") }.map { it.drop(2) }, lines.filter { it.startsWith("t:") }.map { it.drop(2) }.toSet())
        }
    }
}

/**
 * ☰ → Draft: Fantasy Football Calculator's ADP for the user's scoring and league size, with last season's points per
 * game; tap a player when someone takes him, long-press when you do. Suggestions follow [DraftAdvice]. The picks are
 * saved in [file], so leaving the screen (or the app) keeps the draft.
 */
@Composable
fun DraftScreen(
    year: Int,
    load: suspend (year: Int, teams: Int, profile: ScoringProfile) -> DraftBoardResult,
    profiles: Flow<ScoringProfile>,
    teams: Int,
    slots: Map<String, Int>?,
    file: File,
    onBack: () -> Unit,
    rounds: Int = 15,
) {
    val profile by profiles.collectAsState(initial = null)
    var board by remember { mutableStateOf<DraftBoardResult?>(null) }
    var picks by remember { mutableStateOf(runCatching { DraftPicks.decode(file.readText()) }.getOrDefault(DraftPicks())) }
    var mockMode by remember { mutableStateOf(false) }
    LaunchedEffect(profile, teams) { profile?.let { board = load(year, teams, it) } }
    fun save(next: DraftPicks) {
        picks = next
        runCatching { file.writeText(next.encode()) }
    }
    val players = board?.players.orEmpty()
    val byKey = players.associateBy { it.key }
    val mine = picks.mine.mapNotNull(byKey::get)
    val available = players.filter { it.key !in picks.taken && it.key !in picks.mine }
    val round = (picks.mine.size + picks.taken.size) / teams.coerceAtLeast(1) + 1
    val suggested = DraftAdvice.suggestions(available, mine, slots ?: Lineups.DEFAULT_SLOTS, round, rounds)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(if (mockMode) "Mock draft $year" else "Draft $year", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = { mockMode = !mockMode }, modifier = Modifier.testTag("draft:mock")) { Text(if (mockMode) "Real draft" else "Mock") }
                if (!mockMode) TextButton(onClick = { save(DraftPicks()) }, modifier = Modifier.testTag("draft:reset")) { Text("Reset") }
            }
            val b = board
            when {
                b == null -> Note("Reading ADP from Fantasy Football Calculator…")
                b.players.isEmpty() -> Note("No board: ${b.message ?: "no ADP yet"}.")
                mockMode -> MockDraftView(b.players, teams.coerceAtLeast(2), rounds, slots ?: Lineups.DEFAULT_SLOTS)
                else -> LazyColumn(Modifier.fillMaxSize().testTag("draft")) {
                    item {
                        Note(
                            "$teams teams · round $round · ADP from ${b.players.size} players (${b.matched} matched to last season). " +
                                "Tap a player when someone takes him; long-press when you do.",
                        )
                    }
                    item { Label("Suggested") }
                    items(suggested, key = { "s:${it.key}" }) { p -> DraftRow(p, "s", onTap = { save(picks.toggleTaken(p.key)) }, onLong = { save(picks.toggleMine(p.key)) }) }
                    item { Label("Your team (${mine.size})") }
                    items(mine, key = { "m:${it.key}" }) { p -> DraftRow(p, "m", onTap = { save(picks.toggleMine(p.key)) }, onLong = { save(picks.toggleMine(p.key)) }) }
                    item { Label("Available") }
                    items(available, key = { "a:${it.key}" }) { p -> DraftRow(p, "a", onTap = { save(picks.toggleTaken(p.key)) }, onLong = { save(picks.toggleMine(p.key)) }) }
                }
            }
        }
    }
}

/**
 * A mock draft against bots ([MockDraft]): pick a slot, start, and tap a player when it's your turn; the bots pick
 * until it comes round again. Nothing is saved: leaving the screen ends it.
 */
@Composable
private fun MockDraftView(players: List<BoardPlayer>, teams: Int, rounds: Int, slots: Map<String, Int>) {
    var slot by remember { mutableStateOf(0) }
    var picks by remember { mutableStateOf<List<String>?>(null) }
    val random = remember { Random(System.nanoTime()) }
    fun advance(next: List<String>) {
        picks = MockDraft.run(players, next, slot, teams, rounds, slots, random)
    }
    val p = picks
    if (p == null) {
        Note("Draft against ${teams - 1} bots that pick by ADP and roster need. $rounds rounds, snake order.")
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { slot = (slot - 1).coerceAtLeast(0) }) { Text("‹") }
            Text("Pick ${slot + 1} of $teams", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { slot = (slot + 1).coerceAtMost(teams - 1) }) { Text("›") }
            TextButton(onClick = { advance(emptyList()) }, modifier = Modifier.testTag("mock:start")) { Text("Start") }
        }
        return
    }
    val byKey = players.associateBy { it.key }
    val taken = p.toSet()
    val available = players.filter { it.key !in taken }
    val done = p.size >= teams * rounds || available.isEmpty()
    val mine = p.indices.filter { MockDraft.team(it, teams) == slot }
    val lastMine = mine.lastOrNull() ?: -1
    val since = (lastMine + 1 until p.size).toList()
    val suggested = if (done) emptyList() else DraftAdvice.suggestions(available, mine.mapNotNull { byKey[p[it]] }, slots, p.size / teams + 1, rounds)
    val draft = { b: BoardPlayer -> if (!done) advance(p + b.key) }
    LazyColumn(Modifier.fillMaxSize().testTag("mock")) {
        item {
            Note(if (done) "Done: your ${mine.size} picks below." else "Your pick: ${MockDraft.label(p.size, teams)}. Tap a player to take him.")
        }
        item {
            TextButton(onClick = { picks = null }, modifier = Modifier.padding(horizontal = 8.dp).testTag("mock:restart")) { Text("Restart") }
        }
        if (!done) {
            item { Label("Taken since your last pick (${since.size})") }
            items(since, key = { "t:$it" }) { n -> byKey[p[n]]?.let { MockRow(it, "${MockDraft.label(n, teams)} · ${draftLine(it)}", "t", null) } }
            item { Label("Suggested") }
            items(suggested, key = { "s:${it.key}" }) { b -> MockRow(b, draftLine(b), "s") { draft(b) } }
        }
        item { Label("Your team (${mine.size})") }
        items(mine, key = { "m:$it" }) { n -> byKey[p[n]]?.let { MockRow(it, "${MockDraft.label(n, teams)} · ${draftLine(it)}", "m", null) } }
        if (!done) {
            item { Label("Available") }
            items(available, key = { "a:${it.key}" }) { b -> MockRow(b, draftLine(b), "a") { draft(b) } }
        }
    }
}

@Composable
private fun MockRow(p: BoardPlayer, line: String, section: String, onTap: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp).testTag("mock:$section:${p.key}"),
    ) {
        Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "ADP 12.4 · WR · CIN · bye 6 · 18.2/g last year". */
internal fun draftLine(p: BoardPlayer): String = listOfNotNull(
    "ADP ${String.format(Locale.US, "%.1f", p.adp)}",
    p.position.let { if (it == "DST") "D/ST" else it },
    p.team,
    p.bye?.let { "bye $it" },
    p.lastPerGame?.let { "${String.format(Locale.US, "%.1f", it)}/g last year" },
).joinToString(" · ")

@Composable
private fun DraftRow(p: BoardPlayer, section: String, onTap: () -> Unit, onLong: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("draft:$section:${p.key}"),
    ) {
        Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(draftLine(p), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
