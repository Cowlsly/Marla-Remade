package com.vayunmathur.speech

import com.vayunmathur.speech.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.StatFs
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.speech.service.WhisperRecognitionService
import com.vayunmathur.speech.util.PiperModel
import com.vayunmathur.speech.util.PiperVoiceRegistry
import com.vayunmathur.speech.util.SpeechSetupActions
import com.vayunmathur.speech.util.SpeechSetupUiState
import com.vayunmathur.speech.util.TtsVoiceUiState
import com.vayunmathur.speech.util.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val ds = remember { DataStoreUtils.getInstance(context) }

    // Whisper
    val modelReady = remember(refresh) { WhisperModel.isReady(context) }

    // Voices — migrate legacy if needed then compute installed list and per-voice states
    val installedCodes = remember(refresh) {
        try {
            PiperVoiceRegistry.migrateLegacyIfNeeded(context)
            PiperVoiceRegistry.installedCodes(context).let { codes ->
                if (codes.isEmpty() && PiperModel.isReady(context)) listOf("en") else codes
            }
        } catch (_: Throwable) {
            if (PiperModel.isReady(context)) listOf("en") else emptyList()
        }
    }
    val ttsVoices = remember(refresh, installedCodes) {
        try {
            PiperVoiceRegistry.ALL.map { def ->
                TtsVoiceUiState(
                    code = def.code,
                    bcp47 = def.bcp47,
                    iso3 = def.iso3,
                    nativeName = def.nativeName,
                    englishName = def.englishName,
                    isInstalled = def.code in installedCodes,
                    progress = 0f, // real progress from DataStore in the card itself
                    sizeEstimateMb = def.sizeEstimateMb,
                    quality = def.quality,
                    sampleRate = def.sampleRate,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
    val installedBytes = remember(refresh) {
        try {
            PiperVoiceRegistry.installedBytes(context)
        } catch (_: Throwable) {
            0L
        }
    }
    val ttsModelReady = remember(refresh, installedCodes) {
        installedCodes.isNotEmpty() || PiperModel.isReady(context)
    }
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
            installedBytes = installedBytes,
            currentTestLang = if (installedCodes.isNotEmpty()) installedCodes.first() else "en",
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

            // Legacy single-voice
            override fun voiceProgress() = PiperVoiceRegistry.overallProgress(ds)
            override suspend fun downloadVoice() {
                PiperVoiceRegistry.download(context, ds, listOf("en"))
                withContext(Dispatchers.IO) {
                    PiperVoiceRegistry.installIfNeeded(context, PiperVoiceRegistry.DEFAULT)
                }
            }

            // Per-voice
            override fun voiceProgress(code: String): Float {
                val def = PiperVoiceRegistry.byCode(code) ?: return 0f
                return PiperVoiceRegistry.progress(ds, def)
            }

            override suspend fun downloadVoice(code: String) {
                val def = PiperVoiceRegistry.byCode(code) ?: return
                PiperVoiceRegistry.download(context, ds, listOf(code))
                withContext(Dispatchers.IO) {
                    PiperVoiceRegistry.installIfNeeded(context, def)
                }
            }

            override fun deleteVoice(code: String) {
                val def = PiperVoiceRegistry.byCode(code) ?: return
                // Immediate file deletion + async DataStore cleanup
                PiperVoiceRegistry.deleteFiles(context, def)
                // Fire-and-forget DataStore reset (suspend) in IO scope
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ds.setDouble("progress_${PiperVoiceRegistry.VOICES_ROOT}/${def.remoteArchive}", 0.0)
                        ds.setLong("dlid_${PiperVoiceRegistry.VOICES_ROOT}/${def.remoteArchive}", 0L)
                        ds.setDouble("speed_${PiperVoiceRegistry.VOICES_ROOT}/${def.remoteArchive}", 0.0)
                    } catch (_: Throwable) {}
                }
                refresh++
            }

            override fun installedCodes(): List<String> = installedCodes
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
            title = "Text-to-speech voices (${state.ttsVoices.count { it.isInstalled }}/${state.ttsVoices.size})",
            done = state.ttsModelReady,
        ) {
            // Storage summary
            if (state.installedBytes > 0) {
                val mb = state.installedBytes / (1024 * 1024)
                Text(
                    text = "Installed: ${mb} MB • ${state.ttsVoices.count { it.isInstalled }} voices",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }

            // WiFi warning — high quality 22kHz ~80-90 MB each
            if (state.ttsVoices.any { !it.isInstalled }) {
                Text(
                    text = "High-quality voices (22kHz) download ~80-90 MB each on Wi-Fi. Total ~1.6 GB if all 20 installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Per-voice rows
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (voice in state.ttsVoices) {
                    VoiceRow(
                        voice = voice,
                        progressOf = { actions.voiceProgress(voice.code) },
                        onDownload = { actions.downloadVoice(voice.code) },
                        onDelete = { actions.deleteVoice(voice.code) },
                        onDone = actions::refresh,
                    )
                }
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
            installedVoices = state.ttsVoices.filter { it.isInstalled },
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

/**
 * A per-voice download row with progress bar, size badge, quality/sample-rate note, and delete.
 */
@Composable
private fun VoiceRow(
    voice: TtsVoiceUiState,
    progressOf: () -> Float,
    onDownload: suspend () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pct by remember { mutableIntStateOf(0) }

    LaunchedEffect(busy) {
        while (busy) {
            pct = (progressOf() * 100f).toInt().coerceIn(0, 100)
            delay(500)
        }
    }

    // Poll progress even when not busy so the bar shows download manager progress
    LaunchedEffect(voice.code, voice.isInstalled) {
        if (!voice.isInstalled) {
            // Light polling while download may be in progress in background
            while (!voice.isInstalled) {
                pct = (progressOf() * 100f).toInt().coerceIn(0, 100)
                delay(1000)
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${voice.nativeName} (${voice.englishName})",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (voice.isInstalled) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "✓",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${voice.code.uppercase()} • ${voice.bcp47} • ${voice.quality} • ${voice.sampleRate} Hz • ~${voice.sizeEstimateMb} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!voice.isInstalled) {
                if (pct in 1..99 || busy) {
                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Downloading… $pct%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { onDownload() }
                            busy = false
                            onDone()
                        }
                    },
                ) {
                    Text(if (busy) "Downloading… $pct%" else "Download ${voice.nativeName} (~${voice.sizeEstimateMb} MB)")
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Installed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(onClick = { onDelete() }) {
                        Text("Delete")
                    }
                }
            }
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
    installedVoices: List<TtsVoiceUiState>,
    currentLang: String,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    var selectedCode by remember(currentLang, installedVoices) {
        mutableStateOf(
            if (installedVoices.any { it.code == currentLang }) currentLang
            else installedVoices.firstOrNull()?.code ?: "en"
        )
    }
    var dropdownExpanded by remember { mutableStateOf(false) }

    // Hold the engine across recompositions and release it when leaving the screen.
    val engine = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        onDispose { engine.value?.shutdown(); engine.value = null }
    }

    // Sample utterances per language (keep short, same length rough).
    val samples = remember {
        mapOf(
            "en" to "Hello, this is the offline Piper voice.",
            "es" to "Hola, esta es la voz sin conexión de Piper.",
            "fr" to "Bonjour, ceci est la voix hors ligne Piper.",
            "de" to "Hallo, das ist die Offline-Piper-Stimme.",
            "it" to "Ciao, questa è la voce offline di Piper.",
            "pt" to "Olá, esta é a voz offline do Piper.",
            "nl" to "Hallo, dit is de offline Piper-stem.",
            "ru" to "Привет, это автономный голос Пайпер.",
            "pl" to "Cześć, to jest głos offline Piper.",
            "tr" to "Merhaba, bu çevrimdışı Piper sesi.",
            "ar" to "مرحبا، هذا هو صوت بايبر دون اتصال.",
            "hi" to "नमस्ते, यह ऑफ़लाइन पाइपर आवाज़ है।",
            "zh" to "你好，这是离线 Piper 语音。",
            "ja" to "こんにちは、これはオフラインのPiper音声です。",
            "ko" to "안녕하세요, 오프라인 Piper 음성입니다.",
            "vi" to "Xin chào, đây là giọng nói ngoại tuyến của Piper.",
            "th" to "สวัสดี นี่คือเสียงออฟไลน์ของ Piper",
            "id" to "Halo, ini adalah suara Piper offline.",
            "uk" to "Привіт, це автономний голос Пайпер.",
            "sv" to "Hej, det här är Pipers offlineröst.",
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.try_the_voice), fontWeight = FontWeight.Bold)

            if (installedVoices.isNotEmpty()) {
                // Language picker for TTS test — using simple ExposedDropdown
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = !dropdownExpanded },
                ) {
                    OutlinedTextField(
                        value = installedVoices.firstOrNull { it.code == selectedCode }
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
                        installedVoices.forEach { voice ->
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
                    if (installedVoices.isEmpty()) {
                        status = "No voice installed yet."
                        return@Button
                    }
                    val chosen = installedVoices.firstOrNull { it.code == selectedCode }
                        ?: installedVoices.first()
                    val bcp47 = chosen.bcp47
                    val sample = samples[chosen.code] ?: samples["en"]!!

                    status = "Loading…"
                    engine.value?.shutdown()
                    var tts: TextToSpeech? = null
                    tts = TextToSpeech(
                        context,
                        { st ->
                            if (st == TextToSpeech.SUCCESS) {
                                val locale = try {
                                    Locale.forLanguageTag(bcp47)
                                } catch (_: Throwable) {
                                    Locale(chosen.code)
                                }
                                tts?.language = locale
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
