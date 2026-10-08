package dev.gridiron.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.gridiron.core.datastore.Look
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
            var times: Any? = null
            app.settings.alerts.collect { alerts ->
                val on = alerts.any
                InjuryAlertWorker.schedule(this@MainActivity, on)
                // New times rebook the waiting run; anything else keeps it.
                GameDayRefreshWorker.schedule(this@MainActivity, alerts.refresh, rebook = times != null && times != alerts.refreshTimes)
                times = alerts.refreshTimes
                // Off forgets what was seen, so turning an alert back on doesn't replay every change since.
                if (!alerts.injury) app.injuryAlerts.forget()
                if (!alerts.news) app.newsAlerts.forget()
                val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (on && !granted) askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            val look by app.settings.look.collectAsState(initial = Look())
            GridironTheme(wallpaper = look.wallpaper, trueBlack = look.trueBlack) {
                GridironNavHost(app.deps)
            }
        }
    }
}
