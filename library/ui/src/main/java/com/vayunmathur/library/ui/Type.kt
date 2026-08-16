package com.vayunmathur.library.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Google Sans Flex — the app suite's brand typeface.
 *
 * Applied to every Material text style via [AppTypography] so the UI always
 * renders in Google Sans Flex and ignores the user's system font choice. Backed
 * by the single variable font (`res/font/google_sans_flex.ttf`); each weight is
 * a [FontVariation] on the `wght` axis (minSdk 31, so variable fonts are fully
 * supported).
 *
 * Monospace / code contexts are intentionally NOT affected: editors and inline
 * code set [FontFamily.Monospace] explicitly rather than reading the theme
 * [Typography], so they keep a fixed-width face.
 */
private fun googleSansFlex(weight: FontWeight): Font = Font(
    resId = R.font.google_sans_flex,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val GoogleSansFlex: FontFamily = FontFamily(
    googleSansFlex(FontWeight.Light),
    googleSansFlex(FontWeight.Normal),
    googleSansFlex(FontWeight.Medium),
    googleSansFlex(FontWeight.SemiBold),
    googleSansFlex(FontWeight.Bold),
)

/** The default Material 3 [Typography] with every style forced to [GoogleSansFlex]. */
val AppTypography: Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.copy(fontFamily = GoogleSansFlex),
        displayMedium = displayMedium.copy(fontFamily = GoogleSansFlex),
        displaySmall = displaySmall.copy(fontFamily = GoogleSansFlex),
        headlineLarge = headlineLarge.copy(fontFamily = GoogleSansFlex),
        headlineMedium = headlineMedium.copy(fontFamily = GoogleSansFlex),
        headlineSmall = headlineSmall.copy(fontFamily = GoogleSansFlex),
        titleLarge = titleLarge.copy(fontFamily = GoogleSansFlex),
        titleMedium = titleMedium.copy(fontFamily = GoogleSansFlex),
        titleSmall = titleSmall.copy(fontFamily = GoogleSansFlex),
        bodyLarge = bodyLarge.copy(fontFamily = GoogleSansFlex),
        bodyMedium = bodyMedium.copy(fontFamily = GoogleSansFlex),
        bodySmall = bodySmall.copy(fontFamily = GoogleSansFlex),
        labelLarge = labelLarge.copy(fontFamily = GoogleSansFlex),
        labelMedium = labelMedium.copy(fontFamily = GoogleSansFlex),
        labelSmall = labelSmall.copy(fontFamily = GoogleSansFlex),
    )
}
