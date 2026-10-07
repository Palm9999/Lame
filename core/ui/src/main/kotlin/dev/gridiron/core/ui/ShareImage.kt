package dev.gridiron.core.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import dev.gridiron.core.designsystem.GridironTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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

    /** Every card's width in pixels, whatever the phone's density: 1080, a feed's full width. */
    public const val WIDTH_PX: Int = 1080

    /** [bitmap] scaled to [WIDTH_PX] wide, keeping its shape. */
    public fun fixedWidth(bitmap: Bitmap): Bitmap {
        if (bitmap.width == WIDTH_PX || bitmap.width <= 0) return bitmap
        val height = (bitmap.height.toLong() * WIDTH_PX / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, WIDTH_PX, height, true)
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

/**
 * The look every shared card has: the app's name on top, the card, and the data credit under it, at a phone's width.
 * Always the light theme, so a card reads the same wherever it's posted, whatever the phone's mode.
 */
@Composable
public fun ShareCardFrame(content: @Composable () -> Unit) = GridironTheme(darkTheme = false) {
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
                                val file = ImageShare.write(context, fileName, ImageShare.fixedWidth(layer.toImageBitmap().asAndroidBitmap()))
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

/**
 * A small stat table for a shared card: [title], an optional [subtitle], then one line per row of [rows] (a label and
 * one cell per [headers] entry). The Grid's and Compare's cards; a long label or header is cut with an ellipsis.
 */
@Composable
public fun StatTableCard(title: String, subtitle: String?, headers: List<String>, rows: List<Pair<String, List<String>>>) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Spacer(Modifier.weight(1f))
        for (h in headers) {
            Text(h, Modifier.width(STAT_CELL), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    for ((label, cells) in rows) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            for (c in cells) Text(c, Modifier.width(STAT_CELL), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, maxLines = 1)
        }
    }
}

private val STAT_CELL = 52.dp
