package dev.gridiron.feature.players

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.TraySlotUi
import dev.gridiron.core.model.CompareSlot
import kotlinx.collections.immutable.ImmutableList

/** Players waiting to be compared. Tap a chip to change its season or weeks; ✕ removes it. */
@Composable
internal fun TrayBar(
    tray: ImmutableList<TraySlotUi>,
    onEdit: (TraySlotUi) -> Unit,
    onRemove: (CompareSlot) -> Unit,
    onCompare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("tray"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tray.forEach { t ->
                    // Keyed by the whole slot: the same player can appear twice.
                    key(t.slot) {
                        InputChip(
                            selected = false,
                            onClick = { onEdit(t) },
                            label = {
                                Column {
                                    Text(t.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                    Text(t.detail, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            },
                            trailingIcon = {
                                Text(
                                    "✕",
                                    Modifier.clickable { onRemove(t.slot) }
                                        .padding(4.dp)
                                        .semantics { contentDescription = "Remove ${t.name}" },
                                )
                            },
                        )
                    }
                }
            }
            Button(onClick = onCompare, enabled = tray.size >= 2, modifier = Modifier.testTag("compareButton")) {
                Text("Compare ${tray.size}")
            }
        }
    }
}
