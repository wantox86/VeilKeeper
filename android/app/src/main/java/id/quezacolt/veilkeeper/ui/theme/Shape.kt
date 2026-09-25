package id.quezacolt.veilkeeper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Phase 6 UI polish (SPEC-BASE.md Section 27): deliberately moderate corner
 * radii so the vault reads as crisp and "notebook-like" rather than the
 * pill-round default Material sample look -- matches the restrained shape
 * language used everywhere else in this pass (Section 27 explicitly warns
 * against "excessive rounded cards everywhere"). Buttons and cards share the
 * same restrained scale.
 */
val VeilKeeperShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
