package id.quezacolt.veilkeeper.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.quezacolt.veilkeeper.ui.theme.Spacing

/**
 * Shared empty/loading/error state views (SPEC-BASE.md Section 27:
 * "Excellent empty states", "Good loading states", "Good error states").
 * Extracted once here instead of duplicated per-screen ad-hoc `Text`/
 * `CircularProgressIndicator` calls, so every screen's states look and
 * behave the same -- a bare spinner and a red line of text is exactly what
 * Section 27 asks this app to *not* look like.
 */

/** A centered icon + title + optional message, for "nothing here yet" states. Optional [actionLabel]/[onAction] for a next-step CTA (e.g. "Add your first secret"). */
@Composable
fun VeilKeeperEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xl)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.md))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Full-space centered loading indicator with an optional label -- used instead of a bare spinner so long operations (login KDF, network calls) don't feel stuck. */
@Composable
fun VeilKeeperLoading(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = label ?: "Loading" })
            if (label != null) {
                Spacer(Modifier.height(Spacing.sm))
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Full-space centered error state: icon + message + optional retry -- replaces bare red `Text` so failures read as an intentional state, not a crash artifact. */
@Composable
fun VeilKeeperErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(Spacing.md))
                TextButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Retry")
                }
            }
        }
    }
}

/**
 * Subtle cross-fade between a screen's loading/error/content states
 * (SPEC-BASE.md Section 27 "subtle animation") -- a plain [Crossfade], not a
 * flashy transition, so switching from a spinner to real content doesn't
 * pop/jump.
 */
@Composable
fun <T> VeilKeeperStateCrossfade(
    targetState: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    Crossfade(targetState = targetState, modifier = modifier, label = "state-crossfade", content = content)
}
