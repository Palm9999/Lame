package dev.gridiron.feature.players

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * Whether the Grid's top bar is shown, driven by the table's scroll direction.
 * Scrolling down by [hideAfterPx] hides it; any scroll up, reaching the top or
 * a new request shows it. It only watches: the connection never consumes scroll.
 */
@Stable
internal class ChromeScrollState(private val hideAfterPx: Float) {
    /** The bar's measured height, reported by the bar's layout. */
    var heightPx: Float by mutableFloatStateOf(0f)

    var hidden: Boolean by mutableStateOf(false)
        private set

    /** 0 while shown, `-heightPx` while hidden. */
    val offsetPx: State<Float> = derivedStateOf { if (hidden) -heightPx else 0f }

    private var travelled = 0f

    fun show() {
        travelled = 0f
        hidden = false
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy < 0f) {
                travelled -= dy
                if (travelled >= hideAfterPx) hidden = true
            } else if (dy > 0f) {
                show()
            }
            return Offset.Zero
        }
    }
}
