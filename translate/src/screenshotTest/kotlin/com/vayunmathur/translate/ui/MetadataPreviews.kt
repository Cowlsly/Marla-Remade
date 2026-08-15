package com.vayunmathur.translate.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.translate.platform.MicState
import com.vayunmathur.translate.platform.TextTranslateActions
import com.vayunmathur.translate.platform.TextTranslateUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:translate`, rendered from Compose previews instead of from an
 * instrumented test on a device. See `common-conventions-preview-metadata`.
 *
 * `./gradlew :translate:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/translate/`, where `release.sh` picks them up.
 *
 * Three things to keep in mind when editing:
 *
 *  - Order comes from the function names. The generated PNG filenames embed them, so
 *    `Preview1Translated`/`Preview2Listening`/... sort into listing order.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in Studio
 *    but is not collected as a screenshot test, which surfaces as "did not discover any
 *    tests". Previews must also be class members, not top-level functions, for the same
 *    reason: the engine discovers them as JUnit tests.
 *  - Everything is a literal. The translations below are written out rather than produced
 *    by the SMaLL-100 model, which is a ~1.2 GB runtime download that no preview can load.
 *
 * Only the text screen is here. The camera screen is a live CameraX viewfinder, and
 * Layoutlib cannot open a camera — a preview of it would be an empty rectangle, which is
 * worse for the listing than leaving it out.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-translated", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Translated() {
        DynamicTheme(darkTheme = true) {
            TextTranslateScreen(
                state = TextTranslateUiState(
                    sourceLang = "en",
                    targetLang = "es",
                    translationAvailable = true,
                    inputText = "Everything here runs on your phone. No account, no cloud, " +
                        "and no network after the model is installed.",
                    outputText = "Todo aquí funciona en tu teléfono. Sin cuenta, sin nube, " +
                        "y sin red después de instalar el modelo.",
                ),
                actions = TextTranslateActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-listening", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Listening() {
        DynamicTheme(darkTheme = true) {
            TextTranslateScreen(
                state = TextTranslateUiState(
                    sourceLang = "auto",
                    targetLang = "ja",
                    translationAvailable = true,
                    inputText = "Where is the nearest train station?",
                    outputText = "最寄りの駅はどこですか？",
                    micState = MicState.LISTENING,
                ),
                actions = TextTranslateActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-translating", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Translating() {
        DynamicTheme(darkTheme = true) {
            TextTranslateScreen(
                state = TextTranslateUiState(
                    sourceLang = "de",
                    targetLang = "fr",
                    translationAvailable = true,
                    inputText = "Die Bibliothek schließt um achtzehn Uhr.",
                    isTranslating = true,
                ),
                actions = TextTranslateActions.Noop,
            )
        }
    }
}
