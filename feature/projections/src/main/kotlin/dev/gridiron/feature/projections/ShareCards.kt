package dev.gridiron.feature.projections

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import dev.gridiron.core.data.live.Grade
import dev.gridiron.core.data.live.ReportCard
import dev.gridiron.core.data.live.WeekRecap
import dev.gridiron.core.data.live.WeekReview
import dev.gridiron.core.projections.TradeOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A card image for the share sheet: written as a PNG under `cache/exports` (beside its final name, then moved into
 * place) and handed over through the app's `<applicationId>.exports` provider, as the Grid's CSV export is.
 */
public object ImageShare {
    public suspend fun write(context: Context, fileName: String, bitmap: Bitmap): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val tmp = File(dir, "$fileName.tmp")
        try {
            tmp.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("couldn't encode the card") }
            val out = File(dir, fileName)
            if (!tmp.renameTo(out)) {
                out.delete()
                if (!tmp.renameTo(out)) throw IOException("couldn't move $tmp to $out")
            }
            out
        } catch (_: IOException) {
            tmp.delete()
            null
        }
    }

    /** Opens the share sheet on [file]; false when the provider can't serve it. */
    public fun send(context: Context, file: File): Boolean {
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share card").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

/** The look every shared card has: the app's name on top, the card, and the data credit under it, at a phone's width. */
@Composable
public fun ShareCardFrame(content: @Composable () -> Unit) {
    Column(
        Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Gridiron", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        content()
        Text(
            "Gridiron · data: nflverse, ESPN",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A preview of [card] with Share, which saves it as [fileName] and opens the share sheet; Cancel or outside closes it. */
@Composable
public fun SharePreview(fileName: String, onDismiss: () -> Unit, card: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                Column(
                    Modifier.drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
                ) { ShareCardFrame(card) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = {
                            scope.launch {
                                val file = ImageShare.write(context, fileName, layer.toImageBitmap().asAndroidBitmap())
                                if (file == null || !ImageShare.send(context, file)) {
                                    Toast.makeText(context, "Couldn't share the card", Toast.LENGTH_SHORT).show()
                                } else {
                                    onDismiss()
                                }
                            }
                        },
                        modifier = Modifier.testTag("share:send"),
                    ) { Text("Share") }
                }
            }
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun CardLine(text: String, strong: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal)
}

private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)

/** The league's latest week, with the user's own result first when there is one. */
@Composable
public fun WeeklyRecapShareCard(recap: WeekRecap, mine: WeekReview?) {
    CardTitle("Week ${recap.week} around the league")
    mine?.takeIf { it.week == recap.week }?.let { CardLine("You scored ${one(it.scored)} of a possible ${one(it.best)}", strong = true) }
    recap.highScore?.let { (team, score) -> CardLine("Top score: $team, ${one(score)}") }
    recap.blowout?.let { g -> CardLine("Biggest win: ${g.winner} over ${g.loser} by ${one(g.margin)}") }
    recap.closest?.let { g -> CardLine("Closest: ${g.winner} over ${g.loser} by ${one(g.margin)}") }
    if (recap.topScorers.isNotEmpty()) {
        CardLine("Best starters", strong = true)
        for ((p, team) in recap.topScorers) CardLine("${p.name} ${one(p.espnPoints ?: 0.0)} ($team)")
    }
}

/** Every manager's overall place and category places, the user's marked. */
@Composable
public fun ReportShareCard(cards: List<ReportCard>, myTeamId: Int?) {
    CardTitle("Report card")
    for (c in cards) {
        val mine = c.teamId == myTeamId
        CardLine("${placeText(c.overall)} · ${c.name}" + if (mine) " (you)" else "", strong = mine)
        Text(
            Grade.entries.mapNotNull { g -> c.places[g]?.let { "${g.label} ${placeText(it)}" } }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A player's week: his projection and likely range, TD chance and rest of season. */
@Composable
public fun PlayerShareCard(name: String, subtitle: String, card: ProjectionCard) {
    CardTitle(name)
    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val week = listOfNotNull("Week ${card.week}", card.matchup).joinToString(" · ")
    when {
        card.bye -> CardLine("$week: bye", strong = true)
        card.notThisWeek -> CardLine("$week: not projected", strong = true)
        card.out -> CardLine("$week: out", strong = true)
        else -> {
            CardLine("$week: ${one(card.points)} pts", strong = true)
            CardLine(listOfNotNull("Likely ${one(card.floor)}–${one(card.ceiling)}", card.tdChance?.let { "TD ${(it * 100).roundToInt()}%" }).joinToString(" · "))
        }
    }
    card.rosPoints?.let { ros -> CardLine("Rest of season ${one(ros)} pts" + (card.rosPerGame?.let { " (${one(it)} per game)" } ?: "")) }
}

/** A trade with [partner]: who goes where, the verdict and what it does to both best lineups. */
@Composable
public fun TradeShareCard(partner: String, give: List<String>, get: List<String>, outcome: TradeOutcome) {
    CardTitle(verdict(outcome))
    CardLine("You send: ${give.joinToString().ifEmpty { "nobody" }}")
    CardLine("You get: ${get.joinToString().ifEmpty { "nobody" }}")
    CardLine("You ${gainText(outcome.myGain)} · $partner ${gainText(outcome.theirGain)} rest of season", strong = true)
}
