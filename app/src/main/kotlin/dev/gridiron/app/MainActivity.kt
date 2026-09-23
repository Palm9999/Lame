package dev.gridiron.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.feature.players.GridRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as GridironApplication
        setContent {
            GridironTheme {
                // Task 13's navigation wires these callbacks to the Compare and scoring-editor screens.
                GridRoute(app.repository, app.scoring, app.tray, onCompare = {}, onEditProfiles = {})
            }
        }
    }
}
