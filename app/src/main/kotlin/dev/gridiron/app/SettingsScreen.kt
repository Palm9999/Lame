package dev.gridiron.app

import android.app.TimePickerDialog
import android.os.Build
import android.text.format.DateFormat
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.SettingsRepository
import dev.gridiron.core.data.live.PropsStatus
import dev.gridiron.core.designsystem.ScreenBar
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The Odds API key and how props went, then the seasons the phone builds,
 * newest first. Changes apply on the next refresh.
 */
@Composable
fun SettingsScreen(settings: SettingsRepository, onBack: () -> Unit, props: Flow<PropsStatus>? = null) {
    val selected by settings.seasons.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar("Settings", onBack)
          // One scrolling page: the sections grew past a phone's height.
          Column(Modifier.verticalScroll(rememberScrollState())) {
            LookSection(settings)
            AlertsSection(settings)
            PropsSection(settings, props)
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
            Column {
                for (season in settings.choices.asReversed()) {
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
}

/** One switch per alert, each on unless turned off. */
@Composable
private fun LookSection(settings: SettingsRepository) {
    val look by settings.look.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val now = look ?: return
    Text("Look", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AlertSwitch("Wallpaper colors", "Take the app's colors from your wallpaper (Material You). The stat heat colors stay the same.", now.wallpaper, "wallpaper") { on ->
            scope.launch { settings.setLook { it.copy(wallpaper = on) } }
        }
    }
    AlertSwitch("True black", "Black backgrounds in dark mode, for OLED screens.", now.trueBlack, "trueBlack") { on ->
        scope.launch { settings.setLook { it.copy(trueBlack = on) } }
    }
}

@Composable
private fun AlertsSection(settings: SettingsRepository) {
    val alerts by settings.alerts.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val now = alerts ?: return
    Text("Alerts", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    AlertSwitch("Injury changes", "A player on your rosters changes ESPN status (checked about every two hours).", now.injury, "injury") { on ->
        scope.launch { settings.setAlerts { it.copy(injury = on) } }
    }
    AlertSwitch("Roster news", "ESPN stories about players on your rosters.", now.news, "news") { on ->
        scope.launch { settings.setAlerts { it.copy(news = on) } }
    }
    AlertSwitch("Lineup checks", "About 90 minutes before kickoff, when a starter in your ESPN lineup is out or on bye.", now.lineup, "lineup") { on ->
        scope.launch { settings.setAlerts { it.copy(lineup = on) } }
    }
    AlertSwitch("Tuesday summary", "Last week's result, your report card place and this week's win chance.", now.summary, "summary") { on ->
        scope.launch { settings.setAlerts { it.copy(summary = on) } }
    }
    AlertSwitch("Game-day refresh", "Rebuilds the stats before each game day, September to February, then notifies you. Tap a day to change its time.", now.refresh, "refresh") { on ->
        scope.launch { settings.setAlerts { it.copy(refresh = on) } }
    }
    if (now.refresh) {
        val context = LocalContext.current
        for ((day, time) in now.refreshTimes) {
            Row(
                Modifier.fillMaxWidth().clickable {
                    TimePickerDialog(context, { _, h, m ->
                        scope.launch { settings.setAlerts { it.copy(refreshTimes = it.refreshTimes + (day to LocalTime.of(h, m))) } }
                    }, time.hour, time.minute, DateFormat.is24HourFormat(context)).show()
                }.padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp).testTag("refresh:${day.name}"),
            ) {
                Text(day.getDisplayName(TextStyle.FULL, Locale.getDefault()), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    AlertSwitch("League drops", "A player dropped in your league who would lift your rest of season (checked about every two hours).", now.drops, "drops") { on ->
        scope.launch { settings.setAlerts { it.copy(drops = on) } }
    }
    AlertSwitch("Quiet hours", "Holds injury and news alerts from 10 pm to 8 am and sends them after. Lineup checks still come.", now.quiet, "quiet") { on ->
        scope.launch { settings.setAlerts { it.copy(quiet = on) } }
    }
}

@Composable
private fun AlertSwitch(title: String, detail: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("alerts:$tag"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The Odds API key, and how the last props fetch went. */
@Composable
private fun PropsSection(settings: SettingsRepository, props: Flow<PropsStatus>?) {
    val saved by settings.oddsApiKey.collectAsState(initial = null)
    var draft by remember(saved) { mutableStateOf(saved.orEmpty()) }
    val scope = rememberCoroutineScope()
    Text(
        "Betting props",
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        "With a free key from the-odds-api.com, each refresh blends the coming week's player props into the projections. " +
            "The key is sent only to The Odds API.",
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f).testTag("oddsKey"),
            label = { Text("Odds API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        TextButton(onClick = { scope.launch { settings.setOddsApiKey(draft) } }, modifier = Modifier.testTag("saveOddsKey")) { Text("Save") }
    }
    val status = props?.collectAsState(initial = null)?.value
    if (status != null) {
        Text(
            propsStatusText(status, hasKey = saved != null),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One line on props, e.g. "412 Odds API credits left. Props fetched Sep 28,
 * 3:10 PM." Without a key, the last key's credits and error no longer apply.
 */
internal fun propsStatusText(
    status: PropsStatus,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    hasKey: Boolean = true,
): String {
    if (!hasKey) return "Add a key to blend the coming week's props into projections."
    return listOfNotNull(
        status.creditsLeft?.let { "$it Odds API credits left." },
        status.fetchedAt?.let { "Props fetched ${formatWhen(it, zone, locale)}." },
        status.error?.let { "Last refresh: $it." },
    ).joinToString(" ").ifEmpty { "Props are fetched on the next refresh." }
}
