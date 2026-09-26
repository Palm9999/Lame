package dev.gridiron.app

import android.widget.Toast
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.SettingsRepository
import kotlinx.coroutines.launch

/** The seasons the phone builds, newest first. Changes apply on the next refresh. */
@Composable
fun SettingsScreen(settings: SettingsRepository, onBack: () -> Unit) {
    val selected by settings.seasons.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "Seasons",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Stats are built for the checked seasons on the next refresh. Each season is about a 25 MB download.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val chosen = selected ?: return@Column
            LazyColumn {
                items(settings.choices.asReversed()) { season ->
                    val checked = season in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox) { now ->
                                scope.launch {
                                    if (!settings.setSelected(season, now)) {
                                        Toast.makeText(context, "Keep at least one season", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("season:$season"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(season.toString(), Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}
