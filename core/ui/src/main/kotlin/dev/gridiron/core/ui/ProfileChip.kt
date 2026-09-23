package dev.gridiron.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import dev.gridiron.core.model.ScoringProfile
import kotlinx.collections.immutable.ImmutableList

/** The active scoring profile; tap to switch, or to edit profiles. */
@Composable
public fun ProfileChip(
    active: ScoringProfile,
    profiles: ImmutableList<ScoringProfile>,
    onSelect: (String) -> Unit,
    onEditProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        AssistChip(
            onClick = { open = true },
            label = { Text("${active.name} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.testTag("profileChip"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(if (p.id == active.id) "✓ ${p.name}" else p.name) },
                    onClick = {
                        open = false
                        onSelect(p.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Edit profiles…") }, onClick = { open = false; onEditProfiles() })
        }
    }
}
