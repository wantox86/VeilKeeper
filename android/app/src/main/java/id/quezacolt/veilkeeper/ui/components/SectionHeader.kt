package id.quezacolt.veilkeeper.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp

/**
 * Phase 3 dashboard layout: a wide-tracked uppercase eyebrow label that
 * introduces a Home screen section ("CATEGORIES" / "RECENT" / "RESULTS"),
 * matching the revamp reference screenshot. Marked as a heading in the
 * semantics tree so TalkBack users can jump between sections.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 3.sp,
        color = MaterialTheme.colorScheme.primary,
    )
}
