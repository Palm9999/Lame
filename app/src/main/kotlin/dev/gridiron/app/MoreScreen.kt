package dev.gridiron.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.designsystem.ScreenBar

/** One destination on the More tab, under [group]. */
data class MoreItem(val group: String, val label: String, val detail: String, val open: () -> Unit)

/** Everything the bottom bar's tabs don't cover, grouped. */
@Composable
fun MoreScreen(items: List<MoreItem>) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        ScreenBar("More", onBack = null)
        LazyColumn(Modifier.fillMaxSize().testTag("more")) {
            items.groupBy { it.group }.forEach { (group, rows) ->
                item(key = "g:$group") {
                    Text(
                        group,
                        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(rows, key = { "i:${it.label}" }) { item ->
                    Column(
                        Modifier.fillMaxWidth().clickable(onClick = item.open).padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("more:${item.label}"),
                    ) {
                        Text(item.label, style = MaterialTheme.typography.bodyLarge)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
