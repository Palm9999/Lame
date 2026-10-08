package dev.gridiron.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's few icons, drawn as 24 dp outlines so the app needs no icon library. Tinted by `Icon` like any other
 * vector: the stroke color here is only a placeholder.
 */
public object GridironIcons {
    /** A table: the Grid. */
    public val Table: ImageVector by lazy {
        outline("Table") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            moveTo(4f, 10f); lineTo(20f, 10f)
            moveTo(4f, 14.5f); lineTo(20f, 14.5f)
            moveTo(9.5f, 5f); lineTo(9.5f, 19f)
        }
    }

    /** A line trending up: projections. */
    public val Trend: ImageVector by lazy {
        outline("Trend") {
            moveTo(3f, 17f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(21f, 7f)
            moveTo(15f, 7f); lineTo(21f, 7f); lineTo(21f, 13f)
        }
    }

    /** A scoreboard: two scores side by side. */
    public val Scores: ImageVector by lazy {
        outline("Scores") {
            moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
            moveTo(12f, 6f); lineTo(12f, 18f)
            moveTo(6.5f, 10f); lineTo(8.5f, 10f); lineTo(8.5f, 14f); lineTo(6.5f, 14f); close()
            moveTo(15.5f, 10f); lineTo(17.5f, 10f); lineTo(17.5f, 14f); lineTo(15.5f, 14f); close()
        }
    }

    /** A page of text: news. */
    public val News: ImageVector by lazy {
        outline("News") {
            moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 20f); lineTo(5f, 20f); close()
            moveTo(8f, 8f); lineTo(16f, 8f)
            moveTo(8f, 12f); lineTo(16f, 12f)
            moveTo(8f, 16f); lineTo(13f, 16f)
        }
    }

    /** Three lines: everything else. */
    public val More: ImageVector by lazy {
        outline("More") {
            moveTo(4f, 7f); lineTo(20f, 7f)
            moveTo(4f, 12f); lineTo(20f, 12f)
            moveTo(4f, 17f); lineTo(20f, 17f)
        }
    }

    /** An arrow pointing left: back. */
    public val Back: ImageVector by lazy {
        outline("Back") {
            moveTo(20f, 12f); lineTo(4f, 12f)
            moveTo(10f, 6f); lineTo(4f, 12f); lineTo(10f, 18f)
        }
    }

    /** A magnifying glass: search. */
    public val Search: ImageVector by lazy {
        outline("Search") {
            moveTo(17f, 10.5f)
            arcTo(6.5f, 6.5f, 0f, true, true, 4f, 10.5f)
            arcTo(6.5f, 6.5f, 0f, true, true, 17f, 10.5f)
            moveTo(15.5f, 15.5f); lineTo(20f, 20f)
        }
    }

    /** A circle with a bang: something went wrong or is missing. */
    public val Alert: ImageVector by lazy {
        outline("Alert") {
            moveTo(21f, 12f)
            arcTo(9f, 9f, 0f, true, true, 3f, 12f)
            arcTo(9f, 9f, 0f, true, true, 21f, 12f)
            moveTo(12f, 7.5f); lineTo(12f, 13f)
            moveTo(12f, 16.5f); lineTo(12f, 16.6f)
        }
    }

    private fun outline(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        ).build()
}
