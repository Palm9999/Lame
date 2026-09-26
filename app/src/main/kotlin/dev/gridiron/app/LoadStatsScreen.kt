package dev.gridiron.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A fresh install: nothing to show until the phone has built its first stats. */
@Composable
fun LoadStatsScreen(state: RefreshState, onLoad: () -> Unit, onSettings: (() -> Unit)?) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Gridiron", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Stats are built on this phone from nflverse's public data. The first load downloads " +
                    "about 25 MB per season and can take a few minutes. It keeps going if you leave the app.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            when (state) {
                is RefreshState.Running -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(state.text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                is RefreshState.Finished -> {
                    if (!state.ok) {
                        Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                    }
                    Button(onClick = onLoad) { Text(if (state.ok) "Load stats" else "Retry") }
                }
                RefreshState.Idle -> Button(onClick = onLoad) { Text("Load stats") }
            }
            if (onSettings != null && state !is RefreshState.Running) {
                TextButton(onClick = onSettings) { Text("Choose seasons") }
            }
        }
    }
}
