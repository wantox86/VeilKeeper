package id.quezacolt.veilkeeper.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Behind the veil" palette (SPEC-BASE.md Section 27/28 Phase 6 pass):
 * candlelight amber on a near-black violet for dark mode, and a warm
 * cream/ink pairing with a deep amber primary for light mode. This reads as
 * "private + secure + modern" without leaning on gradients or glassmorphism
 * (both explicitly discouraged by Section 27) -- amber against near-black
 * violet is calm and premium rather than alarm-toned; a small
 * blue-grey tertiary is reserved for rare accents (e.g. the lock/shield
 * brand mark) rather than sprinkled everywhere, so it stays "subtle" per the
 * spec's own wording.
 *
 * Every pairing below (onX rendered on X) was chosen for comfortable WCAG AA
 * contrast: near-black ink text on warm cream surfaces in light mode,
 * near-white text on near-black violet surfaces in dark mode, with the
 * amber/violet accents always paired with a genuinely light or genuinely
 * dark "on" color rather than a mid-tone that would fail contrast. Light
 * mode's primary is a deep amber (rather than the brighter candlelight tone)
 * specifically so amber-on-light text/buttons clears AA -- the brighter
 * candlelight amber lives on as the dark-mode primary and as a container
 * tone.
 */

// Light scheme
val LightPrimary = Color(0xFF7A4E00)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFF6DCA6)
val LightOnPrimaryContainer = Color(0xFF3A2600)

val LightSecondary = Color(0xFF5C4A6B)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFF0E9DE)
val LightOnSecondaryContainer = Color(0xFF1C1A22)

val LightTertiary = Color(0xFF33526B)
val LightOnTertiary = Color(0xFFFFFFFF)
// Derived in-family with LightTertiary (the reference palette doesn't pin an
// explicit tertiary container tone) -- a light tint of the tertiary blue-grey
// with a dark, high-contrast "on" color.
val LightTertiaryContainer = Color(0xFFD7E3EA)
val LightOnTertiaryContainer = Color(0xFF0F2733)

val LightBackground = Color(0xFFFBF7F0)
val LightOnBackground = Color(0xFF1C1A22)
val LightSurface = Color(0xFFFFFDF8)
val LightOnSurface = Color(0xFF1C1A22)
val LightSurfaceVariant = Color(0xFFF0E9DE)
val LightOnSurfaceVariant = Color(0xFF4A4453)
val LightOutline = Color(0xFFC9C2D4)

val LightError = Color(0xFF9A2B22)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFBE1DC)
val LightOnErrorContainer = Color(0xFF43100B)

// Dark scheme
val DarkPrimary = Color(0xFFE8B04B)
val DarkOnPrimary = Color(0xFF2B1D00)
val DarkPrimaryContainer = Color(0xFF4A3410)
val DarkOnPrimaryContainer = Color(0xFFF3D69A)

val DarkSecondary = Color(0xFFCDBBD8)
val DarkOnSecondary = Color(0xFF2F2438)
val DarkSecondaryContainer = Color(0xFF262130)
val DarkOnSecondaryContainer = Color(0xFFE7E1F2)

val DarkTertiary = Color(0xFF9FB6C8)
val DarkOnTertiary = Color(0xFF2F2438)
// Derived in-family with DarkTertiary (see LightTertiaryContainer note above)
// -- a darkened tone of the tertiary blue-grey with a light "on" color.
val DarkTertiaryContainer = Color(0xFF2A3B47)
val DarkOnTertiaryContainer = Color(0xFFCFE0EC)

val DarkBackground = Color(0xFF121019)
val DarkOnBackground = Color(0xFFE7E1F2)
val DarkSurface = Color(0xFF1A1723)
val DarkOnSurface = Color(0xFFE7E1F2)
val DarkSurfaceVariant = Color(0xFF262130)
val DarkOnSurfaceVariant = Color(0xFFB3ABC7)
val DarkOutline = Color(0xFF4A4358)

val DarkError = Color(0xFFF2A0A0)
val DarkOnError = Color(0xFF4A1F1F)
val DarkErrorContainer = Color(0xFF4A1F1F)
val DarkOnErrorContainer = Color(0xFFFFD9D9)

/** A slightly elevated card surface, used sparingly (e.g. content block cards) so cards read as one step above the background without a heavy shadow/gradient. */
val LightSurfaceContainer = Color(0xFFF5EFE4)
val DarkSurfaceContainer = Color(0xFF201C2B)
