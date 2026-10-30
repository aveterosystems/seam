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
 * Seam's teal, the same on the phone and the Mac, instead of wallpaper colours. Teal is for
 * accents only (buttons, switches, highlights); backgrounds and cards are neutral grey, so the
 * app doesn't look tinted. Accent values are the design tokens in the boards' android.css.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF006B5E), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9FF2E0), onPrimaryContainer = Color(0xFF00201B),
    secondaryContainer = Color(0xFFCCE8E1), onSecondaryContainer = Color(0xFF06201B),
    tertiaryContainer = Color(0xFFCFE5FF), onTertiaryContainer = Color(0xFF001D33),
    background = Color(0xFFF8F9FA), onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF8F9FA), onSurface = Color(0xFF1A1C1E), onSurfaceVariant = Color(0xFF44474A),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F2F4),
    surfaceContainer = Color(0xFFEAECEE), surfaceContainerHigh = Color(0xFFE4E6E8), surfaceContainerHighest = Color(0xFFDEE0E2),
    outline = Color(0xFF74777A), outlineVariant = Color(0xFFC4C7CA),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF83D5C4), onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF005047), onPrimaryContainer = Color(0xFF9FF2E0),
    secondaryContainer = Color(0xFF334B46), onSecondaryContainer = Color(0xFFCCE8E1),
    tertiaryContainer = Color(0xFF2E4A63), onTertiaryContainer = Color(0xFFCFE5FF),
    background = Color(0xFF111214), onBackground = Color(0xFFE3E3E6),
    surface = Color(0xFF111214), onSurface = Color(0xFFE3E3E6), onSurfaceVariant = Color(0xFFC4C7CA),
    surfaceContainerLowest = Color(0xFF0C0D0F), surfaceContainerLow = Color(0xFF1B1C1E),
    surfaceContainer = Color(0xFF1F2022), surfaceContainerHigh = Color(0xFF292A2C), surfaceContainerHighest = Color(0xFF343537),
    outline = Color(0xFF8E9194), outlineVariant = Color(0xFF44474A),
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
