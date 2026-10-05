package dev.gridiron.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.gridiron.core.designsystem.GridironTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val askToNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as GridironApplication
        // Injury alerts follow the Settings switch; turned on, they need Android's leave to notify (asked once here,
        // and Android stops asking after the user declines twice).
        lifecycleScope.launch {
            app.settings.injuryAlerts.collect { on ->
                InjuryAlertWorker.schedule(this@MainActivity, on)
                // Off forgets what was seen, so turning alerts back on doesn't replay every change since.
                if (!on) {
                    app.injuryAlerts.forget()
                    app.newsAlerts.forget()
                }
                val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (on && !granted) askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            GridironTheme {
                GridironNavHost(app.deps)
            }
        }
    }
}
