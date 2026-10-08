package dev.gridiron.app

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.RosterRepository
import dev.gridiron.core.designsystem.ScreenBar
import dev.gridiron.core.designsystem.playerClick
import dev.gridiron.core.model.Roster
import kotlinx.coroutines.launch

/** Create, rename and delete rosters, and remove players from them. Players are added from their player page. */
@Composable
fun RostersScreen(repo: RosterRepository, players: PlayerDirectory?, onBack: () -> Unit, onPlayer: (String) -> Unit) {
    val rosters by repo.rosters.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(rosters) {
        val ids = rosters.flatMap { it.playerIds }.toSet() - names.keys
        if (players != null && ids.isNotEmpty()) {
            names = names + ids.mapNotNull { id -> players.header(id)?.let { id to it.name } }
        }
    }
    // null: closed; "": creating; otherwise the id being renamed.
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Roster?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar("Rosters", onBack) {
                TextButton(onClick = { editing = "" }, Modifier.testTag("roster:new")) { Text("New roster") }
            }
            if (rosters.isEmpty()) {
                Text(
                    "No rosters yet. Create one, then add players from their player page.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn {
                for (roster in rosters) {
                    item(key = "h:${roster.id}") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${roster.name} (${roster.playerIds.size})",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            TextButton(onClick = { editing = roster.id }) { Text("Rename") }
                            TextButton(onClick = { deleting = roster }) { Text("Delete") }
                        }
                    }
                    items(roster.playerIds, key = { "p:${roster.id}:$it" }) { id ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                names[id] ?: id,
                                Modifier.weight(1f).playerClick(id, onPlayer).padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { scope.launch { repo.remove(roster.id, id) } }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { id ->
        val current = rosters.firstOrNull { it.id == id }
        NameDialog(
            title = if (current == null) "New roster" else "Rename roster",
            initial = current?.name.orEmpty(),
            onDismiss = { editing = null },
            onSave = { name ->
                editing = null
                scope.launch { if (current == null) repo.create(name) else repo.rename(current.id, name) }
            },
        )
    }
    deleting?.let { roster ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${roster.name}?") },
            text = { Text("Its ${roster.playerIds.size} players are removed from this roster only.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; scope.launch { repo.delete(roster.id) } }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.testTag("roster:name"))
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
