package dev.gridiron.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.MetricInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun MetricSheet(info: MetricInfo, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text(info.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(info.abbr, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(info.definition, style = MaterialTheme.typography.bodyLarge)
            info.formula?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            info.predicts?.let {
                Spacer(Modifier.height(12.dp))
                Text("Predicts: $it", style = MaterialTheme.typography.bodyMedium)
            }
            info.stability?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Stability %.2f: how well this carries over from one season to the next (1 = perfectly).".format(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
