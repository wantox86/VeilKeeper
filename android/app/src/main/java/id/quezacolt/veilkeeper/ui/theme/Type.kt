package id.quezacolt.veilkeeper.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Deliberately small set of overrides on top of Material 3's default type
 * scale (Section 27 "strong typography / clear hierarchy") -- not a bespoke
 * font or a full custom scale, per Section 56 Rule 1 (no overengineering).
 * We keep the platform system font (no new font dependency) and only adjust
 * weight/letter-spacing on the handful of styles this app actually uses a
 * lot: the app title/headline, section titles, and body/label text used for
 * vault content, so the hierarchy between "screen title" -> "section header"
 * -> "content" is unambiguous at a glance.
 */
private val base = Typography()

val VeilKeeperTypography = Typography(
    headlineMedium = base.headlineMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = base.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = base.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = base.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = base.titleSmall.copy(
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = base.bodyLarge.copy(
        lineHeight = 22.sp,
    ),
    bodyMedium = base.bodyMedium.copy(
        lineHeight = 20.sp,
    ),
    labelLarge = base.labelLarge.copy(
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = base.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
    ),
)

/** App wordmark style ("VeilKeeper" on Login/Register) -- distinct from any section headline so the brand mark never gets confused with page titles. */
val BrandTitleStyle: TextStyle
    get() = base.headlineMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
    )
