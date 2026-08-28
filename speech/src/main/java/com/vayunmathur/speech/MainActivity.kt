package com.vayunmathur.speech

import com.vayunmathur.speech.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.speech.domain.SupertonicVoices
import com.vayunmathur.speech.platform.SupertonicBundle
import com.vayunmathur.speech.service.WhisperRecognitionService
import com.vayunmathur.speech.util.SpeechSetupActions
import com.vayunmathur.speech.util.SpeechSetupUiState
import com.vayunmathur.speech.util.TtsVoiceUiState
import com.vayunmathur.speech.util.WhisperModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                SetupScreen()
            }
        }
    }
}

/** Reads the device state and binds it to the stateless [SpeechSetupScreen]. */
@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    // Recompute status whenever [refresh] changes (bumped when returning from settings).
    val isDefault = remember(refresh) {
        val current = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        val mine = ComponentName(context, WhisperRecognitionService::class.java).flattenToString()
        current != null && ComponentName.unflattenFromString(current) ==
            ComponentName.unflattenFromString(mine)
    }
    val hasMic = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    // Whisper
    val modelReady = remember(refresh) { WhisperModel.isReady(context) }

    // Every language the bundle covers. Not a per-install fact any more: the four networks and the
    // ten voices ship in the APK, so this list is the same on every device that has the asset.
    val ttsVoices = remember {
        SupertonicVoices.ALL.map { language ->
            TtsVoiceUiState(
                code = language.code,
                bcp47 = language.bcp47,
                nativeName = language.nativeName,
                englishName = language.englishName,
            )
        }
    }
    val ttsModelReady = remember(refresh) { SupertonicBundle.isPresent(context) }
    val isTtsDefault = remember(refresh) {
        Settings.Secure.getString(context.contentResolver, "tts_default_synth") == context.packageName
    }

    val launchMicRequest = rememberPermissionRequest(
        Manifest.permission.RECORD_AUDIO
    ) { refresh++ }

    SpeechSetupScreen(
        state = SpeechSetupUiState(
            modelReady = modelReady,
            hasMic = hasMic,
            isRecognizerDefault = isDefault,
            ttsModelReady = ttsModelReady,
            isTtsDefault = isTtsDefault,
            ttsVoices = ttsVoices,
            currentTestLang = SupertonicVoices.DEFAULT.code,
        ),
        actions = object : SpeechSetupActions {
            override fun requestMicPermission() = launchMicRequest()

            override fun openVoiceInputSettings() {
                val localeIntent = Intent(Settings.ACTION_LOCALE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val voiceInputIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val candidates = listOf(
                    localeIntent,
                    voiceInputIntent,
                    Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                )
                var launched = false
                for (i in candidates) {
                    if (runCatching { context.startActivity(i) }.isSuccess) {
                        launched = true
                        break
                    }
                }
                if (!launched) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
                refresh++
            }

            override fun openTtsSettings() {
                runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
                refresh++
            }

            override fun refresh() { refresh++ }
        },
    )
}

/**
 * The setup screen, with no dependency on the device so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 *
 * The two test cards below build a SpeechRecognizer / TextToSpeech only when their button is
 * pressed, never during composition, so they render unchanged under Layoutlib.
 *
 * Must keep `Scaffold { padding -> Column(.padding(padding).verticalScroll)` structure
 * without a TopAppBar per fix 91ecb8f5b — titles-only cards.
 */
@Composable
fun SpeechSetupScreen(state: SpeechSetupUiState, actions: SpeechSetupActions) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        StepCard(
            index = 1,
            title = "Speech recognition model",
            done = state.modelReady,
        ) {
            // The recogniser is bundled in the APK, so there is nothing to download here; the
            // card stays as step 1 only to keep the numbering users see stable.
            if (!state.modelReady) {
                Text(
                    text = "The bundled recognition model could not be read. Reinstalling the app should fix it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        StepCard(
            index = 2,
            title = "Microphone access",
            done = state.hasMic,
        ) {
            if (!state.hasMic) {
                Button(onClick = actions::requestMicPermission) {
                    Text(stringResource(R.string.grant_microphone))
                }
            }
        }

        StepCard(
            index = 3,
            title = "Set as speech recognizer",
            done = state.isRecognizerDefault,
        ) {
            OutlinedButton(onClick = actions::openVoiceInputSettings) {
                Text(stringResource(R.string.open_voice_input_settings))
            }
        }

        TestSection(enabled = state.hasMic)

        StepCard(
            index = 4,
            title = "Text-to-speech voices",
            done = state.ttsModelReady,
        ) {
            if (state.ttsModelReady) {
                Text(
                    text = "${state.ttsVoices.size} languages and " +
                        "${SupertonicVoices.VOICES.size} voices, bundled with the app. " +
                        "Nothing to download.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.ttsVoices.joinToString(" · ") { it.nativeName },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "The bundled voices could not be read. Reinstalling the app should fix it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        StepCard(
            index = 5,
            title = "Set as text-to-speech engine",
            done = state.isTtsDefault,
        ) {
            OutlinedButton(onClick = actions::openTtsSettings) {
                Text(stringResource(R.string.open_text_to_speech_settings))
            }
        }

        TtsTestSection(
            enabled = state.ttsModelReady,
            languages = state.ttsVoices,
            currentLang = state.currentTestLang,
        )
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    title: String,
    done: Boolean,
    action: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$index. $title" + if (done) "  ✓" else "",
                fontWeight = FontWeight.Bold,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            action()
        }
    }
}

@Composable
private fun TestSection(enabled: Boolean) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.try_it), fontWeight = FontWeight.Bold)
            Button(
                enabled = enabled,
                onClick = {
                    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                        status = "No recognizer selected yet (finish step 2)."
                        return@Button
                    }
                    result = ""
                    status = "Listening…"
                    val sr = SpeechRecognizer.createSpeechRecognizer(context)
                    sr.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { status = "Transcribing…" }
                        override fun onError(error: Int) { status = "Error ($error)"; sr.destroy() }
                        override fun onResults(results: Bundle?) {
                            result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull().orEmpty()
                            status = ""
                            sr.destroy()
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    }
                    sr.startListening(intent)
                },
            ) { Text(stringResource(R.string.test_microphone)) }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
            if (result.isNotBlank()) Text(stringResource(R.string.heard, result))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsTestSection(
    enabled: Boolean,
    languages: List<TtsVoiceUiState>,
    currentLang: String,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    var selectedCode by remember(currentLang, languages) {
        mutableStateOf(
            if (languages.any { it.code == currentLang }) currentLang
            else languages.firstOrNull()?.code ?: "en"
        )
    }
    var dropdownExpanded by remember { mutableStateOf(false) }

    // Hold the engine across recompositions and release it when leaving the screen.
    val engine = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        onDispose { engine.value?.shutdown(); engine.value = null }
    }

    // One sample sentence per language the bundle covers, and the reason each is written out rather
    // than translated at runtime is that the model reads *characters*: a sentence has to exercise
    // the script, not just the language, or a missing codepoint in the table goes unnoticed.
    val samples = remember {
        mapOf(
            "en" to "Hello, this voice runs entirely on this device.",
            "ar" to "مرحبا، هذا الصوت يعمل بالكامل على هذا الجهاز.",
            "bg" to "Здравейте, този глас работи изцяло на това устройство.",
            "cs" to "Dobrý den, tento hlas běží celý na tomto zařízení.",
            "da" to "Hej, denne stemme kører helt på denne enhed.",
            "de" to "Hallo, diese Stimme läuft vollständig auf diesem Gerät.",
            "el" to "Γεια σας, αυτή η φωνή λειτουργεί εξ ολοκλήρου σε αυτή τη συσκευή.",
            "es" to "Hola, esta voz funciona por completo en este dispositivo.",
            "et" to "Tere, see hääl töötab täielikult selles seadmes.",
            "fi" to "Hei, tämä ääni toimii kokonaan tässä laitteessa.",
            "fr" to "Bonjour, cette voix fonctionne entièrement sur cet appareil.",
            "hi" to "नमस्ते, यह आवाज़ पूरी तरह इस उपकरण पर चलती है।",
            "hr" to "Zdravo, ovaj glas radi u cijelosti na ovom uređaju.",
            "hu" to "Üdvözlöm, ez a hang teljesen ezen a készüléken fut.",
            "id" to "Halo, suara ini berjalan sepenuhnya di perangkat ini.",
            "it" to "Ciao, questa voce funziona interamente su questo dispositivo.",
            "ja" to "こんにちは。この音声はすべてこの端末で動いています。",
            "ko" to "안녕하세요. 이 음성은 전부 이 기기에서 동작합니다.",
            "lt" to "Sveiki, šis balsas veikia visiškai šiame įrenginyje.",
            "lv" to "Sveiki, šī balss darbojas pilnībā šajā ierīcē.",
            "nl" to "Hallo, deze stem werkt volledig op dit apparaat.",
            "pl" to "Cześć, ten głos działa całkowicie na tym urządzeniu.",
            "pt" to "Olá, esta voz funciona inteiramente neste dispositivo.",
            "ro" to "Bună, această voce rulează complet pe acest dispozitiv.",
            "ru" to "Здравствуйте, этот голос работает полностью на этом устройстве.",
            "sk" to "Dobrý deň, tento hlas beží celý na tomto zariadení.",
            "sl" to "Zdravo, ta glas deluje popolnoma na tej napravi.",
            "sv" to "Hej, den här rösten körs helt på den här enheten.",
            "tr" to "Merhaba, bu ses tamamen bu cihazda çalışıyor.",
            "uk" to "Вітаю, цей голос працює повністю на цьому пристрої.",
            "vi" to "Xin chào, giọng nói này chạy hoàn toàn trên thiết bị này.",
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.try_the_voice), fontWeight = FontWeight.Bold)

            if (languages.isNotEmpty()) {
                // Language picker for TTS test — using simple ExposedDropdown
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = !dropdownExpanded },
                ) {
                    OutlinedTextField(
                        value = languages.firstOrNull { it.code == selectedCode }
                            ?.let { "${it.nativeName} (${it.englishName})" } ?: selectedCode,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Voice language") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        colors = TextFieldDefaults.colors(),
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                    ) {
                        languages.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text("${voice.nativeName} · ${voice.englishName} (${voice.code})") },
                                onClick = {
                                    selectedCode = voice.code
                                    dropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Button(
                enabled = enabled,
                onClick = {
                    if (languages.isEmpty()) {
                        status = "The bundled voices could not be read."
                        return@Button
                    }
                    val chosen = languages.firstOrNull { it.code == selectedCode }
                        ?: languages.first()
                    val bcp47 = chosen.bcp47
                    val sample = samples[chosen.code] ?: samples["en"]!!

                    status = "Loading…"
                    engine.value?.shutdown()
                    var tts: TextToSpeech? = null
                    tts = TextToSpeech(
                        context,
                        { st ->
                            if (st == TextToSpeech.SUCCESS) {
                                tts?.language = Locale.forLanguageTag(bcp47)
                                tts?.speak(
                                    sample,
                                    TextToSpeech.QUEUE_FLUSH, null, "sample",
                                )
                                status = ""
                            } else {
                                status = "Engine failed to start."
                            }
                        },
                        context.packageName,
                    )
                    engine.value = tts
                },
            ) { Text(stringResource(R.string.speak_sample)) }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
        }
    }
}
