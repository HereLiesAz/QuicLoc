package com.hereliesaz.quicloc

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * QuicLoc's Compose theme. The palette is pulled straight from the app icon —
 * the location-pin's blue gradient (primary) and the "LOC" purple→magenta
 * lettering (secondary) — laid over a dark neutral ground. The app forces dark
 * mode (see MainActivity), so there's a single dark scheme.
 */

// ---- Icon-derived hues ----------------------------------------------------
private val PinBlue = Color(0xFF29B6F6)       // bright pin blue (reads well on dark)
private val PinBlueDeep = Color(0xFF0D47A1)   // deep end of the pin gradient
private val LocPurple = Color(0xFFC341E8)     // the "LOC" purple→magenta
private val LocPurpleDeep = Color(0xFF4A0E5C)
private val CyanAccent = Color(0xFF80D8FF)    // light cyan highlight

private val QuicLocDarkColors = darkColorScheme(
    primary = PinBlue,
    onPrimary = Color(0xFF002439),
    primaryContainer = PinBlueDeep,
    onPrimaryContainer = Color(0xFFD6EAFF),

    secondary = LocPurple,
    onSecondary = Color(0xFF2A0033),
    secondaryContainer = LocPurpleDeep,
    onSecondaryContainer = Color(0xFFF3D6FF),

    tertiary = CyanAccent,
    onTertiary = Color(0xFF00363F),
    tertiaryContainer = Color(0xFF004E5A),
    onTertiaryContainer = Color(0xFFB8EAFF),

    background = Color(0xFF121316),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF1A1C20),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF272B33),
    onSurfaceVariant = Color(0xFFC2C7CF),
    outline = Color(0xFF8A9099),
    outlineVariant = Color(0xFF3A3F47),

    error = Color(0xFFFF5449),
    onError = Color(0xFF2A0000),
    errorContainer = Color(0xFF5C1614),
    onErrorContainer = Color(0xFFFFD9D6),
)

// Slightly rounder corners than the Material default so the compartmentalized
// cards read as distinct, soft panels.
private val QuicLocShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun QuicLocTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = QuicLocDarkColors,
        shapes = QuicLocShapes,
        content = content,
    )
}
