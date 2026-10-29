// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Seam's teal, the same on the phone and the Mac, instead of wallpaper colours. Values are the
 * design tokens in the boards' android.css (light and dark).
 */
private val Light = lightColorScheme(
    primary = Color(0xFF006B5E), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9FF2E0), onPrimaryContainer = Color(0xFF00201B),
    secondaryContainer = Color(0xFFCCE8E1), onSecondaryContainer = Color(0xFF06201B),
    tertiaryContainer = Color(0xFFCFE5FF), onTertiaryContainer = Color(0xFF001D33),
    background = Color(0xFFF4FBF8), onBackground = Color(0xFF161D1B),
    surface = Color(0xFFF4FBF8), onSurface = Color(0xFF161D1B), onSurfaceVariant = Color(0xFF3F4946),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEEF5F2),
    surfaceContainer = Color(0xFFE8EFEC), surfaceContainerHigh = Color(0xFFE2EAE7), surfaceContainerHighest = Color(0xFFDCE4E1),
    outline = Color(0xFF6F7976), outlineVariant = Color(0xFFBEC9C5),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF83D5C4), onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF005047), onPrimaryContainer = Color(0xFF9FF2E0),
    secondaryContainer = Color(0xFF334B46), onSecondaryContainer = Color(0xFFCCE8E1),
    tertiaryContainer = Color(0xFF2E4A63), onTertiaryContainer = Color(0xFFCFE5FF),
    background = Color(0xFF0E1513), onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0E1513), onSurface = Color(0xFFDDE4E1), onSurfaceVariant = Color(0xFFBEC9C5),
    surfaceContainerLowest = Color(0xFF090F0E), surfaceContainerLow = Color(0xFF161D1B),
    surfaceContainer = Color(0xFF1A211F), surfaceContainerHigh = Color(0xFF252B2A), surfaceContainerHighest = Color(0xFF2F3634),
    outline = Color(0xFF89938F), outlineVariant = Color(0xFF3F4946),
    error = Color(0xFFFFB4AB),
)

@Composable
fun SeamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** The design's `ok` green for a healthy link: not a Material role. */
val okColor: Color
    @Composable @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) Color(0xFF7BD88F) else Color(0xFF1C7C3A)
