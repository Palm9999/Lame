package dev.gridiron.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.gridiron.core.designsystem.GridironTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as GridironApplication
        setContent {
            GridironTheme {
                GridironNavHost(app.deps)
            }
        }
    }
}
