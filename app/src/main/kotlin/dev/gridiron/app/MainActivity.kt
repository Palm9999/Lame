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
        val repository = (application as GridironApplication).repository
        setContent {
            GridironTheme { GridRoute(repository) }
        }
    }
}
