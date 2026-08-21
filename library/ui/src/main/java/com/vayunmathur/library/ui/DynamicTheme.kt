@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.vayunmathur.library.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The app theme: Material You colour extraction on a Material 3 Expressive foundation.
 *
 * [MaterialExpressiveTheme] rather than `MaterialTheme` because it is what populates
 * `MaterialTheme.shapes` (the full `extraSmall`..`extraExtraLarge` scale, including the
 * `*Increased` roles) and `MaterialTheme.motionScheme`, and what flips the flag components
 * read to pick their Expressive defaults. Without it `MaterialTheme.shapes` carries only the
 * classic five roles, which is why corner radii ended up as call-site literals.
 *
 * Shapes, typography and motion are deliberately not overridden here: Material's own
 * defaults are the token set, so there is nothing for us to restate and nothing to drift
 * from it on a dependency bump.
 */
@Composable
fun DynamicTheme(darkTheme: Boolean? = null, content: @Composable () -> Unit) {
    val isDark = darkTheme ?: isSystemInDarkTheme()
    val colorScheme = if (isDark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
