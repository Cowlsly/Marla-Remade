package com.vayunmathur.games.solitaire.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Player preferences that are not part of a game's state. */
class SolitaireSettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("solitaire_settings", Context.MODE_PRIVATE)

    fun getCardColorScheme(): CardColorScheme =
        prefs.getString(CARD_COLORS_KEY, null)
            ?.let { runCatching { CardColorScheme.valueOf(it) }.getOrNull() }
            ?: CardColorScheme.TWO_COLOUR

    fun setCardColorScheme(scheme: CardColorScheme) {
        prefs.edit { putString(CARD_COLORS_KEY, scheme.name) }
    }

    private companion object {
        const val CARD_COLORS_KEY = "card_color_scheme"
    }
}
