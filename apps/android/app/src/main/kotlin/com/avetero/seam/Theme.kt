// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import android.app.Activity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LifecycleResumeEffect

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
    inverseSurface = Color(0xFF2F3133), inverseOnSurface = Color(0xFFF1F0F4),
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
    inverseSurface = Color(0xFFE3E3E6), inverseOnSurface = Color(0xFF2F3133),
    outline = Color(0xFF8E9194), outlineVariant = Color(0xFF44474A),
    error = Color(0xFFFFB4AB),
)

/** The prototype's colours that aren't Material roles: `--ok`, the low-battery amber, `--scrim`. */
private class Extra(val ok: Color, val warn: Color, val scrim: Color)

private val LightExtra = Extra(Color(0xFF1C7C3A), Color(0xFF9A5200), Color.Black.copy(alpha = 0.32f))
private val DarkExtra = Extra(Color(0xFF7BD88F), Color(0xFFF5A524), Color.Black.copy(alpha = 0.5f))
private val LocalExtra = compositionLocalOf { LightExtra }

/**
 * Material 3 Expressive with Seam's fixed teal (never wallpaper colours), moving on the
 * prototype's curves. It follows the system theme, and when that changes every colour eases from
 * one theme to the other over 0.4 s, as the prototype's screen does; the system bars' icons follow.
 */
@Composable
fun SeamTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val t by animateFloatAsState(if (dark) 1f else 0f, Motion.ease(400), label = "theme")
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialExpressiveTheme(colorScheme = blend(Light, Dark, t), motionScheme = SeamMotionScheme) {
        val extra = Extra(lerp(LightExtra.ok, DarkExtra.ok, t), lerp(LightExtra.warn, DarkExtra.warn, t), lerp(LightExtra.scrim, DarkExtra.scrim, t))
        // The prototype's text: 14 sp, the font's own line height, no extra tracking
        CompositionLocalProvider(LocalExtra provides extra, LocalTextStyle provides TextStyle(fontSize = 14.sp, letterSpacing = 0.sp), content = content)
    }
}

/** Every role part way from [a] to [b]. */
private fun blend(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme {
    if (t == 0f) return a
    if (t == 1f) return b
    fun l(x: Color, y: Color) = lerp(x, y, t)
    return a.copy(
        primary = l(a.primary, b.primary), onPrimary = l(a.onPrimary, b.onPrimary),
        primaryContainer = l(a.primaryContainer, b.primaryContainer), onPrimaryContainer = l(a.onPrimaryContainer, b.onPrimaryContainer),
        inversePrimary = l(a.inversePrimary, b.inversePrimary),
        secondary = l(a.secondary, b.secondary), onSecondary = l(a.onSecondary, b.onSecondary),
        secondaryContainer = l(a.secondaryContainer, b.secondaryContainer), onSecondaryContainer = l(a.onSecondaryContainer, b.onSecondaryContainer),
        tertiary = l(a.tertiary, b.tertiary), onTertiary = l(a.onTertiary, b.onTertiary),
        tertiaryContainer = l(a.tertiaryContainer, b.tertiaryContainer), onTertiaryContainer = l(a.onTertiaryContainer, b.onTertiaryContainer),
        background = l(a.background, b.background), onBackground = l(a.onBackground, b.onBackground),
        surface = l(a.surface, b.surface), onSurface = l(a.onSurface, b.onSurface),
        surfaceVariant = l(a.surfaceVariant, b.surfaceVariant), onSurfaceVariant = l(a.onSurfaceVariant, b.onSurfaceVariant),
        surfaceTint = l(a.surfaceTint, b.surfaceTint),
        inverseSurface = l(a.inverseSurface, b.inverseSurface), inverseOnSurface = l(a.inverseOnSurface, b.inverseOnSurface),
        error = l(a.error, b.error), onError = l(a.onError, b.onError),
        errorContainer = l(a.errorContainer, b.errorContainer), onErrorContainer = l(a.onErrorContainer, b.onErrorContainer),
        outline = l(a.outline, b.outline), outlineVariant = l(a.outlineVariant, b.outlineVariant), scrim = l(a.scrim, b.scrim),
        surfaceBright = l(a.surfaceBright, b.surfaceBright), surfaceDim = l(a.surfaceDim, b.surfaceDim),
        surfaceContainerLowest = l(a.surfaceContainerLowest, b.surfaceContainerLowest), surfaceContainerLow = l(a.surfaceContainerLow, b.surfaceContainerLow),
        surfaceContainer = l(a.surfaceContainer, b.surfaceContainer), surfaceContainerHigh = l(a.surfaceContainerHigh, b.surfaceContainerHigh),
        surfaceContainerHighest = l(a.surfaceContainerHighest, b.surfaceContainerHighest),
    )
}

/** The design's `ok` green for a healthy link: not a Material role. */
val okColor: Color
    @Composable @ReadOnlyComposable
    get() = LocalExtra.current.ok

/** The low-battery amber: not a Material role. */
val warnColor: Color
    @Composable @ReadOnlyComposable
    get() = LocalExtra.current.warn

/** The prototype's `--scrim` behind sheets and dialogs. */
val scrimColor: Color
    @Composable @ReadOnlyComposable
    get() = LocalExtra.current.scrim

/**
 * The approved prototype's motion (docs/design/prototypes/android-app.html), to the millisecond.
 * Each element names its own duration, as the prototype's CSS does; the curves are its own.
 */
object Motion {
    /** The prototype's `--spring`: overshoots about 8%, then settles. */
    val Spring: Easing = CssLinear(
        "0,.009,.035 2.1%,.141 4.4%,.723 12.9%,.938 16.7%,1.017 19.6%,1.067 22.6%,1.077 25%," +
            "1.065 28.4%,1.019 35.6%,.999 41.1%,.993 47.3%,1.001 66.6%,1",
    )

    /** `--bouncy`: lands, bounces twice, settles. The cookie's morph and a radio's dot. */
    val Bouncy: Easing = CssLinear(
        "0,.004,.016,.035,.063,.098,.141 13.6%,.25,.391,.563,.765,1,.891 40.9%,.848,.813,.785,.766,.754,.75," +
            ".754,.766,.785,.813,.848,.891 68.2%,1 72.7%,.973,.953,.941,.938,.941,.953,.973,1,.988,.984,.988,1",
    )

    /** `--emph`, and CSS's own `ease`, `ease-in` and `ease-out`. */
    val Emphasized: Easing = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val Ease: Easing = CubicBezierEasing(.25f, .1f, .25f, 1f)
    val EaseIn: Easing = CubicBezierEasing(.42f, 0f, 1f, 1f)
    val EaseOut: Easing = CubicBezierEasing(0f, 0f, .58f, 1f)

    fun <T> spring(ms: Int, delay: Int = 0): FiniteAnimationSpec<T> = tween(ms, delay, Spring)
    fun <T> bouncy(ms: Int): FiniteAnimationSpec<T> = tween(ms, 0, Bouncy)
    fun <T> ease(ms: Int, delay: Int = 0): FiniteAnimationSpec<T> = tween(ms, delay, Ease)
    fun <T> emphasized(ms: Int, delay: Int = 0): FiniteAnimationSpec<T> = tween(ms, delay, Emphasized)

    /** How long a confirmation ("✓ Sent to Mac") stays before the label goes back. */
    const val CONFIRM_MS = 1300L

    /** The status dot's ring, the hero's turn, Tor's dash flow. */
    const val PULSE_MS = 2400
    const val TURN_MS = 40_000
    const val DASH_FLOW_MS = 900
}

/** Material's own components (switches, ripples, the app bar) move on the same curves. */
private object SeamMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = Motion.spring(500)
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = Motion.spring(300)
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = Motion.spring(800)
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = Motion.ease(400)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(150, easing = Motion.EaseIn)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = Motion.ease(600)
}

/**
 * True when the system animator scale is 0 (Remove animations): loops stop and movement becomes
 * a crossfade (motion.md, "Reduce Motion is handled"). Re-read on resume, as it's changed elsewhere.
 */
@Composable
fun reducedMotion(): Boolean {
    var off by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    LifecycleResumeEffect(Unit) {
        off = !ValueAnimator.areAnimatorsEnabled()
        onPauseOrDispose {}
    }
    return off
}
