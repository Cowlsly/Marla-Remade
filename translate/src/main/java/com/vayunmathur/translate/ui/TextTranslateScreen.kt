package com.vayunmathur.translate.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.translate.R
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCamera
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconSpeak
import com.vayunmathur.library.ui.IconStop
import com.vayunmathur.library.ui.IconSwapLanguages
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.platform.AndroidSpeechRecognizer
import com.vayunmathur.translate.platform.MicState
import com.vayunmathur.translate.platform.SpeechRecognizerEngine
import com.vayunmathur.translate.platform.TextTranslateActions
import com.vayunmathur.translate.platform.TextTranslateUiState
import com.vayunmathur.translate.platform.TranslateViewModel
import com.vayunmathur.translate.ui.components.LanguagePicker
import kotlinx.coroutines.delay

/** Debounce window for live translation as the user types (ms). */
private const val TRANSLATE_DEBOUNCE_MS = 400L

/**
 * Binds [TranslateViewModel] to the stateless [TextTranslateScreen].
 *
 * Everything a `@Preview` cannot supply lives here: the speech recognizer, the microphone
 * permission launcher, the clipboard, and the debounced call into the translation engine.
 *
 * The NLLB-200 model is auto-installed on open via
 * [com.vayunmathur.library.downloadservice.InitialModelDownloadChecker] in MainActivity
 * (like OpenAssistant), so this screen never needs to show a manual Download button. By the
 * time we get here the files are on disk and [TranslateViewModel] has loaded the engine.
 */
@Composable
fun TextTranslatePage(
    viewModel: TranslateViewModel,
    initialText: String,
    onOpenCamera: () -> Unit,
) {
    val context = LocalContext.current
    val messenger = rememberMessenger()

    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val translationAvailable by viewModel.translationAvailable.collectAsState()

    var inputText by remember { mutableStateOf(initialText) }
    var outputText by remember { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }

    // --- Live speech-to-text engine ---
    // The platform SpeechRecognizer, which routes to whichever RecognitionService the user
    // has selected. Installing the Speech app makes that fully offline (Whisper) without
    // this app needing its own model — see the <queries> entry in the manifest, which is
    // what lets us see an installed recognizer at all on Android 11+.
    val speech: SpeechRecognizerEngine = remember(context) { AndroidSpeechRecognizer(context) }
    var micState by remember { mutableStateOf(MicState.IDLE) }
    var speechError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(speech) { onDispose { speech.destroy() } }

    fun startListening() {
        speechError = null
        micState = MicState.LISTENING
        speech.start(
            languageCode = sourceLang,
            onPartial = { inputText = it },
            onFinal = {
                if (it.isNotBlank()) inputText = it
                micState = MicState.IDLE
            },
            onError = {
                speechError = it
                micState = MicState.IDLE
            },
            // Mic closed; the model is now transcribing. Only advance from LISTENING so a
            // late callback can't resurrect the button after we're already idle.
            onEndOfSpeech = { if (micState == MicState.LISTENING) micState = MicState.TRANSCRIBING },
        )
    }

    val micPermission = rememberPermissionRequest(
        Manifest.permission.RECORD_AUDIO
    ) { granted ->
        if (granted) startListening() else speechError = "Microphone permission is required"
    }

    // Live, debounced translation as input / language selection changes.
    LaunchedEffect(inputText, sourceLang, targetLang, translationAvailable) {
        if (inputText.isBlank()) {
            outputText = ""
            isTranslating = false
            return@LaunchedEffect
        }
        if (!translationAvailable) return@LaunchedEffect
        delay(TRANSLATE_DEBOUNCE_MS)
        isTranslating = true
        outputText = viewModel.translate(inputText).orEmpty()
        isTranslating = false
    }

    // Read here, not in the callback below: stringResource is @Composable.
    val missingVoiceMessage = stringResource(
        R.string.no_voice_installed,
        Languages.byCode(targetLang).englishName,
    )
    val installLabel = stringResource(R.string.install_voice)

    TextTranslateScreen(
        state = TextTranslateUiState(
            sourceLang = sourceLang,
            targetLang = targetLang,
            translationAvailable = translationAvailable,
            inputText = inputText,
            outputText = outputText,
            isTranslating = isTranslating,
            micState = micState,
            speechError = speechError,
        ),
        actions = object : TextTranslateActions {
            override fun setSource(code: String) = viewModel.setSource(code)
            override fun setTarget(code: String) = viewModel.setTarget(code)
            override fun swap() = viewModel.swap()
            override fun setInput(text: String) {
                inputText = text
            }

            // Idle → start. Listening → end capture; the recognizer still delivers its last
            // result via onFinal, it does not cancel. Transcribing → busy finishing.
            override fun toggleMic() {
                when (micState) {
                    MicState.LISTENING -> speech.stop()
                    MicState.TRANSCRIBING -> Unit
                    MicState.IDLE -> micPermission()
                }
            }

            override fun copyOutput() = copyToClipboard(context, outputText)

            // Say so rather than let the engine read the translation out in the device's
            // language, and offer the engine's own voice-download screen as the fix.
            override fun speakOutput() {
                viewModel.speak(outputText, targetLang) {
                    messenger.show(
                        message = missingVoiceMessage,
                        actionLabel = installLabel,
                        onAction = { installVoiceData(context) },
                    )
                }
            }

            override fun openCamera() = onOpenCamera()
        },
    )
}

/**
 * The text translation screen, with no dependency on the ViewModel so it can be rendered
 * from a `@Preview` — see `src/screenshotTest`, which is where the store listing images
 * come from.
 */
@Composable
fun TextTranslateScreen(state: TextTranslateUiState, actions: TextTranslateActions) {
    AppScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            IconButton(onClick = actions::openCamera) {
                IconCamera()
            }
        },
        floatingActionButton = {
            // Idle → Mic (starts). Listening → Stop (ends capture). Transcribing → a spinner
            // that ignores taps, so the user can't double-start or hit "busy".
            FloatingActionButton(
                onClick = actions::toggleMic,
                containerColor = when (state.micState) {
                    MicState.LISTENING -> MaterialTheme.colorScheme.errorContainer
                    MicState.TRANSCRIBING -> MaterialTheme.colorScheme.surfaceVariant
                    MicState.IDLE -> MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                when (state.micState) {
                    MicState.LISTENING -> IconStop()
                    MicState.TRANSCRIBING -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    MicState.IDLE -> IconMic()
                }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LanguageBar(
                sourceCode = state.sourceLang,
                targetCode = state.targetLang,
                onSource = actions::setSource,
                onTarget = actions::setTarget,
                onSwap = actions::swap,
            )

            OutlinedTextField(
                value = state.inputText,
                onValueChange = actions::setInput,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                label = { Text(stringResource(R.string.enter_text)) },
                minLines = 4,
            )

            val micStatus = when (state.micState) {
                MicState.LISTENING -> "Listening…"
                MicState.TRANSCRIBING -> "Transcribing…"
                MicState.IDLE -> null
            }
            micStatus?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            state.speechError?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }

            OutputCard(
                translationAvailable = state.translationAvailable,
                isTranslating = state.isTranslating,
                outputText = state.outputText,
                onCopy = actions::copyOutput,
                onSpeak = actions::speakOutput,
            )
        }
    }
}

@Composable
private fun LanguageBar(
    sourceCode: String,
    targetCode: String,
    onSource: (String) -> Unit,
    onTarget: (String) -> Unit,
    onSwap: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LanguagePicker(
            selectedCode = sourceCode,
            options = Languages.SOURCES,
            onSelected = onSource,
            modifier = Modifier.width(140.dp),
        )
        IconButton(
            onClick = onSwap,
            // Nothing to swap into while the source is auto-detect.
            enabled = sourceCode != Languages.AUTO.code,
        ) {
            IconSwapLanguages()
        }
        LanguagePicker(
            selectedCode = targetCode,
            options = Languages.TARGETS,
            onSelected = onTarget,
            modifier = Modifier.width(140.dp),
        )
    }
}

@Composable
private fun OutputCard(
    translationAvailable: Boolean,
    isTranslating: Boolean,
    outputText: String,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!translationAvailable) {
                // Should never happen — the outer InitialModelDownloadChecker
                // downloads the model before any Navigation is composed, and
                // TranslateViewModel loads the engine on init. Show a loading
                // spinner if the engine is still initializing.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.loading_translator), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.translation),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (isTranslating) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            Text(
                text = outputText.ifBlank { "Translation will appear here" },
                color = if (outputText.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy, enabled = outputText.isNotBlank()) {
                    IconCopy(Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.copy))
                }
                TextButton(onClick = onSpeak, enabled = outputText.isNotBlank()) {
                    IconSpeak(Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.speak))
                }
            }
        }
    }
}

/**
 * Open the TTS engine's voice-download screen. Not every engine declares the activity, so
 * a missing handler is just a no-op rather than a crash.
 */
private fun installVoiceData(context: Context) {
    val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (t: Throwable) {
        Log.w("TextTranslate", "no activity for ACTION_INSTALL_TTS_DATA", t)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("translation", text))
}
