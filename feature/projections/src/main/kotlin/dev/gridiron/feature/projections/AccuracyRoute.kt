package dev.gridiron.feature.projections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.AccuracyRow

/** Loads the season's accuracy summary and renders [AccuracyScreen]. No
 * ViewModel -- this screen has no interaction, just a one-shot read. */
@Composable
public fun AccuracyRoute(
    season: Int,
    repository: AccuracyRepository,
    modifier: Modifier = Modifier,
) {
    var rows by remember { mutableStateOf<List<AccuracyRow>>(emptyList()) }
    LaunchedEffect(season) { rows = repository.summary(season) }
    AccuracyScreen(rows, modifier)
}
