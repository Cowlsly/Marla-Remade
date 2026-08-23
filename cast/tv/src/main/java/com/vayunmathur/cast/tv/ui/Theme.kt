package com.vayunmathur.cast.tv.ui

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * The theme for every screen in this app.
 *
 * **Always dark, and not dynamic.** `DynamicTheme` was the wrong shape here twice over: a television
 * has no wallpaper to derive a colour scheme from, and there is no light mode to offer because nobody
 * has ever wanted a white screen filling a wall in a dim room. The old call site passed
 * `darkTheme = true` unconditionally, which was that conclusion reached one argument short.
 *
 * **Paints nothing.** Deliberately not `Surface`, and not a `Box` with the scheme's background either:
 * one of the two things wrapped in this theme is an overlay above a decoder surface, and anything
 * opaque here would be a solid rectangle over the video for the whole session. Screens that want a
 * background say so themselves - see `ReceiverContent`.
 */
@Composable
fun CastTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
