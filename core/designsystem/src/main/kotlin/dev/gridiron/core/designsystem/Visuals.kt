package dev.gridiron.core.designsystem

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Each NFL team's primary color, by nflverse's team code. */
public object TeamColors {
    private val PRIMARY = mapOf(
        "ARI" to 0xFF97233F, "ATL" to 0xFFA71930, "BAL" to 0xFF241773, "BUF" to 0xFF00338D, "CAR" to 0xFF0085CA,
        "CHI" to 0xFF0B162A, "CIN" to 0xFFFB4F14, "CLE" to 0xFF311D00, "DAL" to 0xFF003594, "DEN" to 0xFFFB4F14,
        "DET" to 0xFF0076B6, "GB" to 0xFF203731, "HOU" to 0xFF03202F, "IND" to 0xFF002C5F, "JAX" to 0xFF006778,
        "KC" to 0xFFE31837, "LA" to 0xFF003594, "LAC" to 0xFF0080C6, "LV" to 0xFF000000, "MIA" to 0xFF008E97,
        "MIN" to 0xFF4F2683, "NE" to 0xFF002244, "NO" to 0xFFD3BC8D, "NYG" to 0xFF0B2265, "NYJ" to 0xFF125740,
        "PHI" to 0xFF004C54, "PIT" to 0xFFFFB612, "SEA" to 0xFF002244, "SF" to 0xFFAA0000, "TB" to 0xFFD50A0A,
        "TEN" to 0xFF0C2340, "WAS" to 0xFF5A1414,
    )

    /** [team]'s color, or null for a code it doesn't know (a free agent, an old code). */
    public fun of(team: String?): Color? = team?.let { PRIMARY[it] }?.let { Color(it) }
}

/** [team]'s code on its own color, ink chosen for contrast; a team with no known color reads as a neutral pill. */
@Composable
public fun TeamChip(team: String, modifier: Modifier = Modifier) {
    val color = TeamColors.of(team) ?: MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(modifier, shape = RoundedCornerShape(4.dp), color = color, contentColor = if (color.luminance() > 0.5f) Color.Black else Color.White) {
        Text(team, Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A thin bar in [team]'s color at the start of a row; nothing for an unknown team, keeping the row's alignment. */
@Composable
public fun TeamStripe(team: String?, modifier: Modifier = Modifier, height: Dp = 28.dp) {
    val color = TeamColors.of(team) ?: Color.Transparent
    Box(modifier.width(3.dp).height(height).background(color, RoundedCornerShape(2.dp)))
}

/**
 * A tiny trend line over [values] (oldest first; null for a missed week, which breaks the line), with the last point
 * dotted. Nothing to draw with fewer than two values.
 */
@Composable
public fun Sparkline(values: List<Double?>, modifier: Modifier = Modifier, width: Dp = 56.dp, height: Dp = 18.dp, description: String = "Trend") {
    val color = ChartColors.series()
    Canvas(modifier.size(width = width, height = height).semantics { contentDescription = description }) {
        val known = values.filterNotNull()
        if (known.size < 2) return@Canvas
        val lo = known.min()
        val hi = known.max()
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        fun y(v: Double) = (size.height - 2.dp.toPx()) * (1 - ((v - lo) / span).toFloat()) + 1.dp.toPx()
        var path: Path? = null
        values.forEachIndexed { i, v ->
            if (v == null) {
                path?.let { drawPath(it, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)) }
                path = null
            } else {
                val p = path
                if (p == null) path = Path().apply { moveTo(i * stepX, y(v)) } else p.lineTo(i * stepX, y(v))
            }
        }
        path?.let { drawPath(it, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)) }
        values.lastOrNull()?.let { drawCircle(color, 2.dp.toPx(), Offset((values.size - 1) * stepX, y(it))) }
    }
}

/**
 * A share (0..1) over time, such as a live win chance: a line from the first point to the last on a fixed 0-100% scale,
 * with a faint 50% line. Nothing to draw with fewer than two points.
 */
@Composable
public fun ShareLine(points: List<Double>, description: String, modifier: Modifier = Modifier) {
    val color = ChartColors.series()
    val mid = ChartColors.track()
    Canvas(modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = description }) {
        drawLine(mid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
        if (points.size < 2) return@Canvas
        val stepX = size.width / (points.size - 1)
        fun y(v: Double) = size.height * (1 - v.coerceIn(0.0, 1.0).toFloat())
        val path = Path().apply {
            moveTo(0f, y(points.first()))
            points.drop(1).forEachIndexed { i, v -> lineTo((i + 1) * stepX, y(v)) }
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 3.dp.toPx(), Offset(size.width, y(points.last())))
    }
}

/**
 * How a projection's outcomes spread: [shares] (each bin's share of draws, low to high over [low]..[high]) as bars,
 * with ticks at the [floor], the projection [mid] and the [ceiling].
 */
@Composable
public fun SpreadChart(shares: List<Double>, low: Double, high: Double, floor: Double, mid: Double, ceiling: Double, modifier: Modifier = Modifier) {
    val color = ChartColors.series()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = "Outcomes from ${floor.toInt()} to ${ceiling.toInt()} points" }) {
        val top = shares.maxOrNull()?.takeIf { it > 0.0 } ?: return@Canvas
        val span = (high - low).takeIf { it > 0.0 } ?: return@Canvas
        val barW = size.width / shares.size
        shares.forEachIndexed { i, s ->
            val h = size.height * 0.85f * (s / top).toFloat()
            val center = low + span * (i + 0.5) / shares.size
            val inside = center in floor..ceiling
            drawRoundRect(
                if (inside) color else color.copy(alpha = 0.35f),
                topLeft = Offset(i * barW + barW * 0.1f, size.height - h),
                size = Size(barW * 0.8f, h),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
        for ((v, w) in listOf(floor to 1.dp, mid to 2.dp, ceiling to 1.dp)) {
            val x = (size.width * ((v - low) / span)).toFloat().coerceIn(0f, size.width)
            drawLine(ink, Offset(x, 0f), Offset(x, size.height), strokeWidth = w.toPx())
        }
    }
}

/** Downloaded images kept for the app's life: a page of headshots is a few hundred KB. */
private val IMAGES = LruCache<String, ImageBitmap>(48)

/**
 * The image at [url] in a circle of [size], fetched once and kept in memory; a neutral circle while it loads, when
 * [url] is null, or when the download fails (offline, no such player).
 */
@Composable
public fun RemoteCircleImage(url: String?, size: Dp, modifier: Modifier = Modifier) {
    var image by remember(url) { mutableStateOf(url?.let { IMAGES.get(it) }) }
    LaunchedEffect(url) {
        if (url == null || image != null) return@LaunchedEffect
        image = withContext(Dispatchers.IO) { download(url) }?.also { IMAGES.put(url, it) }
    }
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, Modifier.size(size), contentScale = ContentScale.Crop) }
    }
}

private fun download(url: String): ImageBitmap? = try {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    try {
        if (connection.responseCode != 200) null else connection.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    } finally {
        connection.disconnect()
    }
} catch (_: Exception) {
    null
}

/** ESPN's headshot for a player's ESPN id, or a team's logo for a D/ST id ("DST_KC"); null when there is neither. */
public fun espnImageUrl(playerId: String, espnId: String?): String? {
    if (playerId.startsWith("DST_")) {
        val code = playerId.removePrefix("DST_").lowercase().let { LOGO_CODES[it] ?: it }
        return "https://a.espncdn.com/i/teamlogos/nfl/500/$code.png"
    }
    return espnId?.let { "https://a.espncdn.com/i/headshots/nfl/players/full/$it.png" }
}

/** nflverse codes ESPN spells differently in its logo paths. */
private val LOGO_CODES = mapOf("la" to "lar", "was" to "wsh")

/** Opens the quick-look sheet for a player id; null where nothing provides one (tests, previews). */
public val LocalPlayerPeek: ProvidableCompositionLocal<((String) -> Unit)?> = staticCompositionLocalOf { null }

/** A player row's taps: a tap opens his page through [onPlayer], a long press the quick look ([LocalPlayerPeek]). */
@OptIn(ExperimentalFoundationApi::class)
public fun Modifier.playerClick(playerId: String, onPlayer: (String) -> Unit): Modifier = composed {
    val peek = LocalPlayerPeek.current
    combinedClickable(onLongClickLabel = peek?.let { "Quick look" }, onLongClick = peek?.let { { it(playerId) } }) { onPlayer(playerId) }
}
