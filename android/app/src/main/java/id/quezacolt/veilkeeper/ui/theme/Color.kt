package id.quezacolt.veilkeeper.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Midnight Vault" palette (SPEC-BASE.md Section 27/28 Phase 6 pass):
 * a deliberately restrained indigo-on-near-black/near-white scheme meant to
 * read as "private + secure + modern" without leaning on gradients or
 * glassmorphism (both explicitly discouraged by Section 27). Indigo was
 * chosen over a "hacker green" or generic teal because it reads as calm and
 * premium rather than alarm-toned; a small violet tertiary is reserved for
 * rare accents (e.g. the lock/shield brand mark) rather than sprinkled
 * everywhere, so it stays "subtle" per the spec's own wording.
 *
 * Every pairing below (onX rendered on X) was chosen for comfortable
 * contrast: near-black text on near-white surfaces in light mode, near-white
 * text on near-black surfaces in dark mode, with the indigo/violet accents
 * always paired with a genuinely light or genuinely dark "on" color rather
 * than a mid-tone that would fail contrast.
 */

// Light scheme
val LightPrimary = Color(0xFF4A55D6)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE0E0FF)
val LightOnPrimaryContainer = Color(0xFF0E1257)

val LightSecondary = Color(0xFF5B6472)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFDFE2EC)
val LightOnSecondaryContainer = Color(0xFF181E29)

val LightTertiary = Color(0xFF7A5AF8)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFE9DDFF)
val LightOnTertiaryContainer = Color(0xFF260469)

val LightBackground = Color(0xFFF8F8FC)
val LightOnBackground = Color(0xFF1B1C21)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF1B1C21)
val LightSurfaceVariant = Color(0xFFE4E5F0)
val LightOnSurfaceVariant = Color(0xFF46474F)
val LightOutline = Color(0xFF767680)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

// Dark scheme
val DarkPrimary = Color(0xFFBCC2FF)
val DarkOnPrimary = Color(0xFF1A2170)
val DarkPrimaryContainer = Color(0xFF333C9E)
val DarkOnPrimaryContainer = Color(0xFFE0E0FF)

val DarkSecondary = Color(0xFFC3C6D6)
val DarkOnSecondary = Color(0xFF2C333F)
val DarkSecondaryContainer = Color(0xFF434B58)
val DarkOnSecondaryContainer = Color(0xFFDFE2EC)

val DarkTertiary = Color(0xFFCFBBFF)
val DarkOnTertiary = Color(0xFF3E1F91)
val DarkTertiaryContainer = Color(0xFF5636A8)
val DarkOnTertiaryContainer = Color(0xFFE9DDFF)

val DarkBackground = Color(0xFF121318)
val DarkOnBackground = Color(0xFFE4E2E9)
val DarkSurface = Color(0xFF121318)
val DarkOnSurface = Color(0xFFE4E2E9)
val DarkSurfaceVariant = Color(0xFF46474F)
val DarkOnSurfaceVariant = Color(0xFFC7C6D0)
val DarkOutline = Color(0xFF90909A)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

/** A slightly elevated card surface, used sparingly (e.g. content block cards) so cards read as one step above the background without a heavy shadow/gradient. */
val LightSurfaceContainer = Color(0xFFF0F0F7)
val DarkSurfaceContainer = Color(0xFF1D1E25)
