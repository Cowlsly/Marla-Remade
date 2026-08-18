package com.vayunmathur.keyboard.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.vayunmathur.keyboard.ui.KeyboardScreen
import com.vayunmathur.keyboard.util.ClipItem
import com.vayunmathur.keyboard.util.ClipboardStore
import com.vayunmathur.keyboard.util.ComposerKind
import com.vayunmathur.keyboard.util.Dictionary
import com.vayunmathur.keyboard.util.EmojiData
import com.vayunmathur.keyboard.util.KeyboardLayouts
import com.vayunmathur.keyboard.util.KeyboardPage
import com.vayunmathur.keyboard.util.KeyboardSettings
import com.vayunmathur.keyboard.util.PinyinDictionary
import com.vayunmathur.keyboard.util.RecentEmoji
import com.vayunmathur.keyboard.util.ShiftState
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * The input method (IME). Renders the keyboard with Compose and turns key actions into edits
 * on the target field's [InputConnection].
 *
 * Compose views need a [LifecycleOwner], [ViewModelStoreOwner] and [SavedStateRegistryOwner]
 * in their view tree; an [InputMethodService] provides none, so this service implements all
 * three and drives the lifecycle from the IME window callbacks.
 */
/** Max gap between two shift taps to latch caps-lock. */
private const val DOUBLE_TAP_MS = 300L

/** How long a freshly copied clip is offered in the strip before the chip gives up the slot. */
private const val CLIP_CHIP_MS = 60_000L

class KeyboardService : InputMethodService(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, ImeActions {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var ds: DataStoreUtils
    private val kbState = KeyboardState()
    private var dictionary: Dictionary = Dictionary.EMPTY

    private val clipboard by lazy { ClipboardStore(File(cacheDir, "clips")) }
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /** Id of the clip the chip is currently offering, so a stale timeout can't clear a newer one. */
    private var chipClipId = 0L

    /** The word currently being composed (underlined) on the letters page. */
    private val composing = StringBuilder()

    /**
     * What is in front of the cursor, mirrored locally rather than read back from the field
     * on every keystroke. Every edit below goes through [commit], [setComposing],
     * [finishComposing] or [deleteBackward] so it stays in step.
     */
    private val before = TextBeforeCursor()

    /** The composition currently shown, so finalizing it can be mirrored into [before]. */
    private var pendingComposition: CharSequence = ""

    /**
     * The composition engine for the active layout, or null for layouts whose keys are
     * already characters. Owns everything about Hangul/pinyin/kana input; the service only
     * applies what it returns to the [InputConnection].
     */
    private var composer: Composer? = null
    private var composerKind: ComposerKind? = null
    private var pinyinSimplified = PinyinDictionary.EMPTY
    private var pinyinTraditional = PinyinDictionary.EMPTY
    private var bopomofoSpellings: Map<String, String> = emptyMap()
    private var loadingChinese = false

    private var lastSpaceTime = 0L
    private var lastShiftTime = 0L

    /** Enter behaviour derived from the current field. */
    private var editorActionId = EditorInfo.IME_ACTION_UNSPECIFIED
    private var enterSendsAction = false

    private var vibrator: Vibrator? = null

    /** This IME's id in the framework, resolved once from [InputMethodManager.getInputMethodList]. */
    private var imeId: String? = null

    /** Layout id -> the framework subtype registered for it, for subtype lookup. */
    private var subtypeByLayoutId: Map<String, InputMethodSubtype> = emptyMap()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        ds = DataStoreUtils.getInstance(this, deviceProtected = true)
        kbState.settings = KeyboardSettings.load(ds)
        vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator

        // Load the dictionary off the main thread; suggestions stay empty until it is ready.
        scope.launch { dictionary = Dictionary.load(this@KeyboardService) }
        scope.launch { kbState.emojiData = EmojiData.load(this@KeyboardService) }
        kbState.recentEmoji = RecentEmoji.decode(ds.getString(KeyboardSettings.Keys.EMOJI_RECENTS))
        scope.launch {
            clipboard.restore(ds.getString(KeyboardSettings.Keys.CLIPS))
            kbState.clips = clipboard.items
        }
        observeClipboard()
        syncComposer()
        registerLayoutSubtypes()
        observeSettings()
    }

    /** Keep [KeyboardState.settings] in sync with DataStore so changes apply live. */
    private fun observeSettings() {
        val keys = KeyboardSettings.Keys
        scope.launch { ds.booleanFlow(keys.HAPTIC).collectLatest { update { copy(haptic = it) } } }
        scope.launch { ds.booleanFlow(keys.SOUND).collectLatest { update { copy(sound = it) } } }
        scope.launch { ds.booleanFlow(keys.AUTO_CAP).collectLatest { update { copy(autoCapitalize = it) } } }
        scope.launch { ds.booleanFlow(keys.DOUBLE_SPACE_PERIOD).collectLatest { update { copy(doubleSpacePeriod = it) } } }
        scope.launch { ds.booleanFlow(keys.SHOW_SUGGESTIONS).collectLatest { update { copy(showSuggestions = it) } } }
        scope.launch { ds.booleanFlow(keys.AUTO_CORRECT).collectLatest { update { copy(autoCorrect = it) } } }
        scope.launch { ds.booleanFlow(keys.NUMBER_ROW).collectLatest { update { copy(numberRow = it) } } }
        scope.launch {
            ds.booleanFlow(keys.CLIPBOARD).collectLatest {
                update { copy(clipboardEnabled = it) }
                if (!it) forgetClips()
            }
        }
        // Settings can wipe the history while the keyboard is running; an empty stored value
        // is that request. Nothing here writes a blank string back, so this cannot loop.
        scope.launch {
            ds.stringFlow(keys.CLIPS).collectLatest { if (it.isBlank()) forgetClips() }
        }
        scope.launch { ds.doubleFlow(keys.KEY_HEIGHT).collectLatest { update { copy(keyHeightScale = it.toFloat()) } } }
        scope.launch {
            ds.stringFlow(keys.ACTIVE_LAYOUT).collectLatest {
                update { copy(activeLayoutId = it) }
                syncComposer()
            }
        }
    }

    // --- Composition engines ---

    /** Point [composer] at whatever the active layout needs, keeping it across no-op changes. */
    private fun syncComposer() {
        val kind = kbState.settings.activeLayout.composer
        if (kind == composerKind && (kind == null || composer != null)) return
        composerKind = kind
        composer = when (kind) {
            null -> null
            ComposerKind.HANGUL -> HangulComposer()
            ComposerKind.ROMAJI -> RomajiComposer()
            ComposerKind.KANA -> KanaKeyComposer()
            ComposerKind.ETHIOPIC -> EthiopicComposer()
            ComposerKind.PINYIN_SIMPLIFIED -> HanComposer(pinyinSimplified, SpellingScheme.Pinyin)
            ComposerKind.PINYIN_TRADITIONAL -> HanComposer(pinyinTraditional, SpellingScheme.Pinyin)
            ComposerKind.BOPOMOFO ->
                HanComposer(pinyinTraditional, SpellingScheme.Bopomofo(bopomofoSpellings))
        }
        if (kind?.needsChineseData == true) loadChineseData()
    }

    /**
     * Read the pinyin tables the first time a Chinese layout is chosen — they are ~130 KiB of
     * asset that a keyboard used for English should never touch. Until they arrive the engine
     * simply has no candidates, and the composer is rebuilt around them once it does.
     */
    private fun loadChineseData() {
        if (loadingChinese || pinyinSimplified !== PinyinDictionary.EMPTY) return
        loadingChinese = true
        scope.launch {
            pinyinSimplified = PinyinDictionary.load(this@KeyboardService, "pinyin_sc")
            pinyinTraditional = PinyinDictionary.load(this@KeyboardService, "pinyin_tc")
            bopomofoSpellings = PinyinDictionary.loadBopomofo(this@KeyboardService)
            loadingChinese = false
            composerKind = null // force a rebuild now that there is something to look up in
            syncComposer()
        }
    }

    private val ComposerKind.needsChineseData: Boolean
        get() = this == ComposerKind.PINYIN_SIMPLIFIED ||
            this == ComposerKind.PINYIN_TRADITIONAL ||
            this == ComposerKind.BOPOMOFO

    /**
     * Apply one composition step: settled text (if any) replaces the composing region and
     * becomes final, then whatever is still being composed goes back under it.
     */
    private fun applyComposition(ic: InputConnection, engine: Composer, result: ComposeResult) {
        if (result.commit.isNotEmpty()) commit(ic, result.commit)
        if (result.composing.isNotEmpty()) {
            setComposing(ic, result.composing)
        } else if (result.commit.isEmpty()) {
            // Nothing settled and nothing left: the last backspace emptied the composition.
            setComposing(ic, "")
            finishComposing(ic)
        }
        kbState.suggestions = engine.candidates
    }

    /** Make the composition final, giving the engine its last chance to rewrite it. */
    private fun finishComposition(ic: InputConnection) {
        val engine = composer ?: return
        if (engine.isComposing) {
            val result = engine.finish()
            if (result != null && result.commit.isNotEmpty()) {
                commit(ic, result.commit)
            } else {
                finishComposing(ic)
            }
        }
        engine.reset()
        kbState.suggestions = emptyList()
    }

    // --- Edits ---

    /**
     * The four ways this service changes the field, each mirrored into [before]. Going
     * through these rather than touching the [InputConnection] directly is what lets
     * auto-capitalisation and the double-space period answer from memory instead of asking
     * the target app — see [TextBeforeCursor].
     */
    private fun commit(ic: InputConnection, text: CharSequence) {
        ic.commitText(text, 1)
        pendingComposition = ""
        before.committed(text)
    }

    private fun setComposing(ic: InputConnection, text: CharSequence) {
        ic.setComposingText(text, 1)
        pendingComposition = text.toString()
        before.composing(text)
    }

    private fun finishComposing(ic: InputConnection) {
        ic.finishComposingText()
        before.composingFinished(pendingComposition)
        pendingComposition = ""
    }

    private fun deleteBackward(ic: InputConnection) {
        ic.deleteSurroundingText(1, 0)
        before.deleted()
    }

    /** The text in front of the cursor, asking the field only when the mirror cannot say. */
    private fun textBeforeCursor(): CharSequence {
        before.peek()?.let { return it }
        val ic = currentInputConnection ?: return ""
        val text = ic.getTextBeforeCursor(TextBeforeCursor.WINDOW, 0) ?: return ""
        before.fill(text)
        return text
    }

    private inline fun update(transform: KeyboardSettings.() -> KeyboardSettings) {
        kbState.settings = kbState.settings.transform()
    }

    override fun onCreateInputView(): View {
        // Compose resolves its per-window recomposer from the IME window's decor view
        // (the ancestor of the framework's `parentPanel`), NOT from our ComposeView.
        // Setting the ViewTree owners only on the ComposeView therefore crashes with
        // "ViewTreeLifecycleOwner not found"; they must live on the window decor view.
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@KeyboardService)
            setViewTreeViewModelStoreOwner(this@KeyboardService)
            setViewTreeSavedStateRegistryOwner(this@KeyboardService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // Some devices dispatch window insets to the input view; capture them when
            // they do (complements the decor-view read in updateBottomInset()).
            setOnApplyWindowInsetsListener { _, insets ->
                kbState.bottomInsetPx = insets.getInsets(
                    android.view.WindowInsets.Type.navigationBars(),
                ).bottom
                insets
            }
            setContent {
                // The framework draws the "hide keyboard" chevron in the navigation bar of the IME
                // window, not inside our Compose tree. Its icon color is the nav-bar icon appearance,
                // which defaults to light (white) icons. In a light theme that renders nearly invisible,
                // so track the theme: light theme -> dark nav-bar icons, dark theme -> light icons.
                val isDark = isSystemInDarkTheme()
                LaunchedEffect(isDark) {
                    window?.window?.let { imeWindow ->
                        WindowInsetsControllerCompat(imeWindow, imeWindow.decorView)
                            .isAppearanceLightNavigationBars = !isDark
                    }
                }
                DynamicTheme {
                    KeyboardScreen(kbState, this@KeyboardService)
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.setLength(0)
        pendingComposition = ""
        before.onStartInput(info.initialSelStart, info.initialSelEnd)
        // Open on whatever subtype the system currently has selected for us, so the app's
        // active layout tracks the framework (e.g. after a system-level switch).
        val current = getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype
        layoutIdOf(current)?.let {
            if (it != kbState.settings.activeLayout.id) applyLayout(it)
        }
        syncComposer()
        composer?.reset()
        configureForEditor(info)
        kbState.suggestions = emptyList()
        kbState.emojiQuery = null
        kbState.emojiResults = emptyList()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        updateAutoCapShift()
        // The listener only fires while we are bound, so anything copied while the keyboard
        // was away is picked up here instead. It is not treated as a password-field copy:
        // this clip predates the field, whoever it came from.
        captureCurrentClip(inPasswordField = false)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        updateBottomInset()
    }

    /**
     * Read the navigation-bar height from the window so the keyboard can pad clear of
     * it. Reads immediately and again after the next layout pass (rootWindowInsets is
     * often not populated yet when onWindowShown/onStartInputView first run).
     */
    private fun updateBottomInset() {
        val decor = window?.window?.decorView ?: return
        val read = {
            decor.rootWindowInsets?.let {
                kbState.bottomInsetPx = it.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            }
        }
        read()
        decor.post { read() }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // Keep the composition alive (STARTED, not DESTROYED) so re-showing is instant.
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        before.onSelectionChanged(newSelStart, newSelEnd)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        composing.setLength(0)
        pendingComposition = ""
        before.invalidate()
        composer?.reset()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        clipListener?.let {
            getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(it)
        }
        clipListener = null
        store.clear()
        scope.cancel()
        super.onDestroy()
    }

    // --- Editor configuration ---

    private fun configureForEditor(info: EditorInfo) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = (cls == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        // Phone gets its own dial-pad layout (FUTO phone.yaml); number/datetime share the
        // numeric layout (FUTO number.yaml). Everything else uses letters.
        val isPhone = cls == InputType.TYPE_CLASS_PHONE
        val isNumeric = cls == InputType.TYPE_CLASS_NUMBER ||
            cls == InputType.TYPE_CLASS_DATETIME

        kbState.passwordField = isPassword
        kbState.textVariation = when {
            cls == InputType.TYPE_CLASS_TEXT &&
                (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> TextVariation.EMAIL
            cls == InputType.TYPE_CLASS_TEXT &&
                variation == InputType.TYPE_TEXT_VARIATION_URI -> TextVariation.URL
            else -> TextVariation.NORMAL
        }
        kbState.basePage = when {
            isPhone -> KeyboardPage.PHONE
            isNumeric -> KeyboardPage.NUMERIC
            else -> KeyboardPage.LETTERS
        }
        kbState.page = kbState.basePage
        kbState.shift = ShiftState.OFF

        // Which action Enter performs, using AOSP LatinIME's precedence:
        //  1. IME_FLAG_NO_ENTER_ACTION  -> Enter is a plain newline, whatever imeOptions says.
        //  2. a custom actionLabel      -> perform info.actionId. This is NOT the imeOptions
        //     action: apps calling setImeActionLabel("Search", id) usually leave imeOptions
        //     at UNSPECIFIED, so reading only imeOptions loses the action entirely and Enter
        //     falls back to a newline.
        //  3. otherwise                 -> perform imeOptions & IME_MASK_ACTION.
        val optionsAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val customLabel = info.actionLabel?.toString()?.takeIf { it.isNotBlank() }

        editorActionId = if (customLabel != null) info.actionId else optionsAction
        enterSendsAction = !noAction && when {
            customLabel != null -> true
            // UNSPECIFIED means "the app didn't say"; treat it as a newline rather than
            // firing action 0, which most multi-line fields do not expect.
            else -> optionsAction != EditorInfo.IME_ACTION_NONE &&
                optionsAction != EditorInfo.IME_ACTION_UNSPECIFIED
        }

        kbState.enterActionLabel = if (enterSendsAction) customLabel else null
        kbState.enterAction = if (!enterSendsAction) EnterAction.RETURN else when (optionsAction) {
            EditorInfo.IME_ACTION_GO -> EnterAction.GO
            EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
            EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
            EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.PREVIOUS
            // A custom action with no recognisable imeOptions action still sends; the key
            // shows the app's own label (enterActionLabel) rather than the return glyph.
            else -> EnterAction.RETURN
        }
    }

    /**
     * Composing (word tracking) is needed for either suggestions or autocorrect, and only
     * makes sense for plain text fields (never passwords or the numeric layout). The only
     * dictionary we ship is English, so it also stays off for every other layout rather than
     * offering English words to someone writing Greek.
     */
    private fun useComposing(): Boolean =
        (kbState.settings.showSuggestions || kbState.settings.autoCorrect) &&
            !kbState.passwordField && kbState.basePage == KeyboardPage.LETTERS &&
            kbState.settings.activeLayout.englishDictionary

    // --- ImeActions ---

    override fun onChar(text: String) {
        feedback()
        // The chip is an offer made before typing starts; the first keystroke declines it.
        dismissClipSuggestion()
        if (typeIntoSearch(text)) return
        val ic = currentInputConnection ?: return
        val engine = composer
        if (engine != null) {
            val result = engine.accept(text)
            if (result != null) {
                applyComposition(ic, engine, result)
                consumeShift()
                return
            }
            // Punctuation, a symbol-page key: settle the composition and type it plainly.
            finishComposition(ic)
            commit(ic, text)
            consumeShift()
            return
        }
        if (useComposing() && text.length == 1 && text[0].isLetter()) {
            composing.append(text)
            setComposing(ic, composing)
            updateSuggestions()
        } else {
            commitCurrentWord(ic, autoCorrect = false)
            commit(ic, text)
            kbState.suggestions = emptyList()
        }
        consumeShift()
        updateAutoCapShift()
    }

    override fun onBackspace() {
        feedback()
        val query = kbState.emojiQuery
        if (query != null) {
            // Backspacing an empty query leaves search rather than deleting from the field
            // the user cannot see.
            if (query.isEmpty()) endEmojiSearch() else setQuery(query.dropLast(1))
            return
        }
        val ic = currentInputConnection ?: return
        val engine = composer
        if (engine != null) {
            // Backspace takes a composition apart one jamo/letter at a time, and only deletes
            // from the field once there is no composition left.
            val result = engine.backspace()
            if (result != null) {
                applyComposition(ic, engine, result)
                return
            }
        }
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            if (composing.isEmpty()) {
                setComposing(ic, "")
                finishComposing(ic)
                kbState.suggestions = emptyList()
            } else {
                setComposing(ic, composing)
                updateSuggestions()
            }
        } else if (before.hasSelection) {
            // Deleting a selection leaves whatever preceded it in front of the cursor, which
            // is text we never saw, so the mirror has to start again.
            commit(ic, "")
            before.invalidate()
        } else {
            deleteBackward(ic)
        }
        updateAutoCapShift()
    }

    override fun onEnter() {
        feedback()
        if (kbState.emojiQuery != null) {
            endEmojiSearch()
            return
        }
        val ic = currentInputConnection ?: return
        // Finish the composing word first: performEditorAction hands control to the app,
        // which would otherwise read the field without the last (still-composing) word.
        finishComposition(ic)
        commitCurrentWord(ic, autoCorrect = false)
        // performEditorAction returns false when the target can't handle the action (a
        // dead connection, or an actionId the app declines). Fall back to a real Enter so
        // the key never silently does nothing.
        val handled = enterSendsAction && ic.performEditorAction(editorActionId)
        if (!handled) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        // Either way the field is now the app's business — it may have inserted a newline,
        // moved focus or submitted and cleared itself.
        before.invalidate()
        kbState.suggestions = emptyList()
        updateAutoCapShift()
    }

    override fun onSpace() {
        feedback()
        // Multi-word emoji names are common ("cat face"), so space is part of the query.
        if (typeIntoSearch(" ")) return
        val ic = currentInputConnection ?: return
        val engine = composer
        if (engine != null) {
            // In a CJK engine space means "take the best candidate", not "type a space".
            val result = engine.space()
            if (result != null) {
                applyComposition(ic, engine, result)
                return
            }
            finishComposition(ic)
            commit(ic, " ")
            lastSpaceTime = SystemClock.uptimeMillis()
            return
        }
        commitCurrentWord(ic, autoCorrect = kbState.settings.autoCorrect)
        val text = textBeforeCursor()
        val now = SystemClock.uptimeMillis()
        val doubleSpace = kbState.settings.doubleSpacePeriod && text.length >= 2 &&
            text[text.length - 1] == ' ' && text[text.length - 2].isLetterOrDigit() &&
            now - lastSpaceTime < 1000
        if (doubleSpace) {
            deleteBackward(ic)
            commit(ic, ". ")
        } else {
            commit(ic, " ")
        }
        lastSpaceTime = now
        kbState.suggestions = emptyList()
        updateAutoCapShift()
    }

    override fun onShift() {
        feedback()
        // Apply shift immediately on every tap; a quick second tap (while already shifted)
        // latches caps-lock. No waiting for a double-tap, so shift feels instant.
        val now = SystemClock.uptimeMillis()
        kbState.shift = when {
            now - lastShiftTime < DOUBLE_TAP_MS && kbState.shift != ShiftState.OFF -> ShiftState.CAPS_LOCK
            kbState.shift == ShiftState.OFF -> ShiftState.SHIFTED
            else -> ShiftState.OFF
        }
        lastShiftTime = now
    }

    override fun onCapsLock() {
        feedback()
        kbState.shift = ShiftState.CAPS_LOCK
    }

    override fun setPage(page: KeyboardPage) {
        feedback()
        if (kbState.emojiQuery != null) {
            kbState.emojiQuery = null
            kbState.emojiResults = emptyList()
        }
        kbState.page = page
    }

    override fun commitSuggestion(word: String) {
        feedback()
        val ic = currentInputConnection ?: return
        val engine = composer
        if (engine != null) {
            // A picked candidate is the character itself; no trailing space, unlike a word.
            applyComposition(ic, engine, engine.pick(word))
            return
        }
        setComposing(ic, word)
        finishComposing(ic)
        commit(ic, " ")
        composing.setLength(0)
        lastSpaceTime = SystemClock.uptimeMillis()
        kbState.suggestions = emptyList()
        updateAutoCapShift()
    }

    /**
     * Register the whole layout catalog with the framework as additional input-method
     * subtypes, so the user enables the ones they want in Android's own "Languages" screen
     * and switches between them with the system globe. The layout id rides along in the
     * subtype's extra value so [onCurrentInputMethodSubtypeChanged] can map a framework
     * switch back to a layout.
     *
     * Several layouts can share a language (English alone has QWERTY, Dvorak, Colemak…), so
     * each subtype needs a distinct name to be tellable apart in the enabler. Naming a
     * subtype requires [InputMethodSubtypeBuilder.setSubtypeNameOverride], added in API 34;
     * below that the extra same-language variants are not registered at all (rather than
     * showing several indistinguishable "English" entries), so each language appears once
     * via its primary variant.
     */
    private fun registerLayoutSubtypes() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val id = imeId ?: imm.inputMethodList
            .firstOrNull { it.packageName == packageName }?.id
        if (id == null) return
        imeId = id
        val nameable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val layouts = if (nameable) {
            KeyboardLayouts.ALL
        } else {
            // One layout per language code (first = primary variant, e.g. en_qwerty, tr_q).
            KeyboardLayouts.ALL.groupBy { it.id.substringBefore('_') }.map { it.value.first() }
        }
        val map = LinkedHashMap<String, InputMethodSubtype>()
        for (layout in layouts) {
            val builder = InputMethodSubtypeBuilder()
                .setSubtypeMode("keyboard")
                .setLanguageTag(layout.id.substringBefore('_'))
                .setSubtypeExtraValue("layoutId=${layout.id}")
                .setSubtypeId(layout.id.hashCode())
                .setSubtypeNameResId(0)
            if (nameable) {
                builder.setSubtypeNameOverride(layout.description)
            }
            map[layout.id] = builder.build()
        }
        subtypeByLayoutId = map
        imm.setAdditionalInputMethodSubtypes(id, map.values.toTypedArray())
    }

    /** Pull the layout id out of a framework subtype, by extra value then language tag. */
    private fun layoutIdOf(subtype: InputMethodSubtype?): String? {
        if (subtype == null) return null
        val fromExtra = subtype.extraValue
            ?.split(',')
            ?.firstOrNull { it.startsWith("layoutId=") }
            ?.substringAfter('=')
        if (fromExtra != null && subtypeByLayoutId.containsKey(fromExtra)) return fromExtra
        val tag = subtype.languageTag
        return KeyboardLayouts.ALL.firstOrNull {
            it.id.substringBefore('_') == tag
        }?.id
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val id = layoutIdOf(newSubtype) ?: return
        if (id == kbState.settings.activeLayout.id) return
        applyLayout(id)
    }

    /**
     * Switch the active layout to [id], committing anything still composing first (the
     * layouts may not share a script). Shared by the framework subtype change and start-up.
     */
    private fun applyLayout(id: String) {
        currentInputConnection?.let {
            finishComposition(it)
            commitCurrentWord(it, autoCorrect = false)
        }

        kbState.settings = kbState.settings.copy(activeLayoutId = id)
        kbState.shift = ShiftState.OFF
        kbState.suggestions = emptyList()
        syncComposer()
        scope.launch { ds.setString(KeyboardSettings.Keys.ACTIVE_LAYOUT, id) }
        updateAutoCapShift()
    }

    // --- Clipboard ---

    /**
     * Watch the system clipboard. An IME may read it while it is the active input method,
     * which is what makes this possible on Android 10+ — but the callback only fires while
     * we are bound, so [onStartInputView] also sweeps the current clip to catch copies made
     * while the keyboard was hidden.
     */
    private fun observeClipboard() {
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            captureCurrentClip(inPasswordField = kbState.passwordField)
        }
        manager.addPrimaryClipChangedListener(listener)
        clipListener = listener
    }

    private fun captureCurrentClip(inPasswordField: Boolean) {
        if (!kbState.settings.clipboardEnabled) return
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
        scope.launch {
            val item = clipboard.capture(this@KeyboardService, clip, inPasswordField)
                ?: return@launch
            val fresh = clipboard.add(item)
            kbState.clips = clipboard.items
            persistClips()
            if (fresh != null) offerClip(fresh)
        }
    }

    /** Show the chip for a newly copied clip, and take it back down after a while. */
    private fun offerClip(item: ClipItem) {
        chipClipId = item.id
        kbState.clipSuggestion = item
        scope.launch {
            delay(CLIP_CHIP_MS)
            if (chipClipId == item.id) dismissClipSuggestion()
        }
    }

    private fun persistClips() {
        val encoded = clipboard.serialize()
        scope.launch { ds.setString(KeyboardSettings.Keys.CLIPS, encoded) }
    }

    /** Drop everything, in memory and on disk — what turning the setting off has to mean. */
    private fun forgetClips() {
        clipboard.clear()
        kbState.clips = emptyList()
        dismissClipSuggestion()
        persistClips()
    }

    override fun pasteClip(item: ClipItem) {
        feedback()
        val ic = currentInputConnection ?: return
        dismissClipSuggestion()
        if (item.isImage) {
            commitImage(ic, item)
        } else {
            finishComposition(ic)
            commitCurrentWord(ic, autoCorrect = false)
            commit(ic, item.text)
            kbState.suggestions = emptyList()
        }
        updateAutoCapShift()
    }

    /**
     * Hand an image clip to the field via `commitContent`. Most fields cannot take one, so
     * the editor's accepted MIME types are checked first rather than committing into the void.
     */
    private fun commitImage(ic: InputConnection, item: ClipItem) {
        val file = item.imageFile ?: return
        val mime = item.mimeType ?: return
        val editor = currentInputEditorInfo ?: return
        val accepted = EditorInfoCompat.getContentMimeTypes(editor)
        if (accepted.none { ClipDescription.compareMimeTypes(mime, it) }) return
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull() ?: return
        val content = InputContentInfoCompat(uri, ClipDescription(item.preview, arrayOf(mime)), null)
        InputConnectionCompat.commitContent(
            ic,
            editor,
            content,
            InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            null,
        )
        // How the field represents an image — if it takes it at all — is up to the app.
        before.invalidate()
    }

    /**
     * Delete a clip. Deleting the newest one also empties the system clipboard: "delete what
     * I just copied" has to mean the password is gone, not merely hidden from our own list.
     */
    override fun deleteClip(item: ClipItem) {
        feedback()
        val newest = clipboard.items.firstOrNull()?.id == item.id
        clipboard.delete(item)
        kbState.clips = clipboard.items
        if (kbState.clipSuggestion?.id == item.id) dismissClipSuggestion()
        if (newest) {
            runCatching { getSystemService(ClipboardManager::class.java)?.clearPrimaryClip() }
        }
        persistClips()
    }

    override fun clearClips() {
        feedback()
        forgetClips()
        runCatching { getSystemService(ClipboardManager::class.java)?.clearPrimaryClip() }
    }

    override fun dismissClipSuggestion() {
        chipClipId = 0L
        kbState.clipSuggestion = null
    }

    // --- Emoji search ---

    override fun startEmojiSearch() {
        feedback()
        kbState.emojiQuery = ""
        kbState.emojiResults = emptyList()
        kbState.page = KeyboardPage.LETTERS
        kbState.shift = ShiftState.OFF
    }

    override fun endEmojiSearch() {
        feedback()
        kbState.emojiQuery = null
        kbState.emojiResults = emptyList()
        kbState.page = KeyboardPage.EMOJI
    }

    override fun commitEmoji(emoji: String) {
        if (kbState.emojiQuery != null) {
            // Picking a result is the end of the search; leave the emoji page behind too,
            // since the user came here to type one thing into their message.
            kbState.emojiQuery = null
            kbState.emojiResults = emptyList()
            kbState.page = kbState.basePage
        }
        rememberEmoji(emoji)
        onChar(emoji)
    }

    /** Keep the recents tab up to date so common emoji stop needing a search at all. */
    private fun rememberEmoji(emoji: String) {
        val recents = RecentEmoji.add(kbState.recentEmoji, emoji)
        if (recents == kbState.recentEmoji) return
        kbState.recentEmoji = recents
        scope.launch {
            ds.setString(KeyboardSettings.Keys.EMOJI_RECENTS, RecentEmoji.encode(recents))
        }
    }

    /** Route a keystroke into the emoji query instead of the field. True if it was consumed. */
    private fun typeIntoSearch(text: String): Boolean {
        val query = kbState.emojiQuery ?: return false
        setQuery(query + text)
        return true
    }

    private fun setQuery(query: String) {
        kbState.emojiQuery = query
        kbState.emojiResults = kbState.emojiData.search(query)
        consumeShift()
    }

    // --- Editing helpers ---

    /** Finish the composing word, optionally replacing it with an autocorrect suggestion. */
    private fun commitCurrentWord(ic: InputConnection, autoCorrect: Boolean) {
        if (composing.isEmpty()) return
        if (autoCorrect) {
            // autocorrect already declines to rewrite a word it knows, so there is no need
            // to look the typed word up separately first.
            val typed = composing.toString()
            val fix = dictionary.autocorrect(typed)
            if (fix != null && !fix.equals(typed, ignoreCase = true)) {
                setComposing(ic, fix)
            }
        }
        finishComposing(ic)
        composing.setLength(0)
    }

    private fun updateSuggestions() {
        if (!kbState.settings.showSuggestions || !useComposing()) {
            kbState.suggestions = emptyList()
            return
        }
        val prefix = composing.toString()
        kbState.suggestions = if (prefix.isBlank()) emptyList() else dictionary.suggestions(prefix, 3)
    }

    private fun consumeShift() {
        if (kbState.shift == ShiftState.SHIFTED) kbState.shift = ShiftState.OFF
    }

    /** Auto-capitalize the shift key when the cursor sits at the start of a sentence. */
    private fun updateAutoCapShift() {
        if (kbState.basePage != KeyboardPage.LETTERS) return
        // Shift is a second character layer, not upper case, in scripts like Devanagari or
        // Thai — auto-capitalizing there would silently swap the whole layout.
        if (!kbState.settings.activeLayout.cased) return
        if (kbState.passwordField || kbState.textVariation != TextVariation.NORMAL) return
        if (kbState.shift == ShiftState.CAPS_LOCK) return
        if (!kbState.settings.autoCapitalize) return
        if (composing.isNotEmpty()) return
        kbState.shift = if (isAtSentenceStart()) ShiftState.SHIFTED else ShiftState.OFF
    }

    private fun isAtSentenceStart(): Boolean {
        val text = textBeforeCursor()
        if (text.isEmpty()) return true
        val last = text[text.length - 1]
        if (last == '\n') return true
        if (text.length < 2) return false
        val prev = text[text.length - 2]
        return last == ' ' && (prev == '.' || prev == '?' || prev == '!')
    }

    // --- Feedback ---

    /**
     * A light key "tick" (like the stock keyboard), not a full-strength buzz. Built once:
     * this runs before the edit on every single keypress, and both the effect and the
     * service lookup were being redone each time.
     */
    private val keyTick by lazy { VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private fun feedback() {
        if (kbState.settings.haptic) {
            vibrator?.vibrate(keyTick)
        }
        if (kbState.settings.sound) {
            audio?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        }
    }
}
