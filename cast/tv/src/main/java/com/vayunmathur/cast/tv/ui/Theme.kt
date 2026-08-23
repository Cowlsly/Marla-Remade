package com.vayunmathur.cast.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * The background is painted here rather than by a `Surface`, so that a full-bleed decoder surface can
 * live under an overlay that draws nothing of its own - the mirror Activity needs transparency above
 * the picture, and a themed `Surface` wrapping everything would be an opaque layer in the way.
 */
@Composable
fun CastTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            content = { content() },
        )
    }
}
