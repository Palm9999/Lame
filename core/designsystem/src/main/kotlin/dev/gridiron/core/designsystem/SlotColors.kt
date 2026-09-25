package dev.gridiron.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** One color per compare slot, in slot order. */
public object SlotColors {
    private val light = listOf(Color(0xFF0072B2), Color(0xFFD55E00), Color(0xFF009E73), Color(0xFFCC79A7))
    private val dark = listOf(Color(0xFF56B4E9), Color(0xFFE69F00), Color(0xFF009E73), Color(0xFFCC79A7))

    @Composable
    public fun color(index: Int): Color {
        val set = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) dark else light
        return set[index.mod(set.size)]
    }
}
