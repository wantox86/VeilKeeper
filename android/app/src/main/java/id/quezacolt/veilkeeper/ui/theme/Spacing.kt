package id.quezacolt.veilkeeper.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Small shared spacing scale (Section 27 "good spacing") -- a plain object
 * of `Dp` constants, not a theming library. Screens already mostly used an
 * ad-hoc 4/8/16/24/32 rhythm; this just names the values so they're used
 * consistently instead of every screen picking its own numbers.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}
