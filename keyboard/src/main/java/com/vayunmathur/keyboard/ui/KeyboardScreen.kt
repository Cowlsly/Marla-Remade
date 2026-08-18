package com.vayunmathur.keyboard.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.keyboard.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.keyboard.ime.EnterAction
import com.vayunmathur.keyboard.ime.ImeActions
import com.vayunmathur.keyboard.ime.KeyboardState
import com.vayunmathur.keyboard.ime.TextVariation
import com.vayunmathur.keyboard.util.KeyboardLayout
import com.vayunmathur.keyboard.util.KeyboardPage
import com.vayunmathur.keyboard.util.Layouts
import com.vayunmathur.keyboard.util.ShiftState
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconBackspace
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconEmoji
import com.vayunmathur.library.ui.IconPaste
import com.vayunmathur.library.ui.IconReturn
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.IconSkipNext
import com.vayunmathur.library.ui.IconSkipPrevious
import com.vayunmathur.library.ui.IconSpaceBar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

/**
 * Root of the keyboard view: a suggestion strip (when enabled) above whichever page of keys
 * is active. All sizing is weight-based so it fills the width; key height scales with the
 * user's height-scale setting.
 *
 * The pieces below are split into their own composables along the lines of what changes
 * while typing: a keystroke produces new suggestions and usually flips shift, and reading
 * either of those here would redraw every key on the keyboard twice per keypress.
 */
@Composable
fun KeyboardScreen(state: KeyboardState, actions: ImeActions) {
    val scale = state.settings.keyHeightScale.coerceIn(0.8f, 1.4f)
    val keyHeight = (52 * scale).dp
    // The IME window doesn't reliably deliver insets to Compose, so pad the bottom by
    // the navigation-bar height the service measured from the window.
    // Clear the system nav bar / gesture area: measured inset plus a comfortable margin,
    // with a floor so there's breathing room even when the inset reads small (gesture nav).
    val bottomInset = with(LocalDensity.current) { state.bottomInsetPx.toDp() }
    val bottomPad = (bottomInset + 18.dp).coerceAtLeast(30.dp)
    // Derived rather than read straight: the query changes on every keystroke of a search,
    // and only arriving at or leaving search should rearrange the keyboard.
    val searching by remember(state) { derivedStateOf { state.emojiQuery != null } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPad),
        ) {
            if (searching) {
                // Search borrows the ordinary letter keys rather than shipping a second
                // keyboard: the query bar and the results sit on top of LettersPage, and the
                // service routes keystrokes into the query while this is showing.
                EmojiSearchStrip(state, actions)
                LettersPage(state, actions, keyHeight)
            } else {
                Strip(state, actions)
                KeyPage(state, actions, keyHeight)
            }
        }
    }
}

/** The emoji-search query bar and its results, which change together on every keystroke. */
@Composable
private fun EmojiSearchStrip(state: KeyboardState, actions: ImeActions) {
    val query = state.emojiQuery ?: return
    EmojiSearchBar(height = StripHeight, query = query, onClose = actions::endEmojiSearch)
    EmojiSearchResults(
        height = StripHeight,
        results = state.emojiResults,
        onPick = actions::commitEmoji,
    )
}

/** Whatever occupies the strip above the keys, or nothing. */
@Composable
private fun Strip(state: KeyboardState, actions: ImeActions) {
    // A fresh clip outranks suggestions because the two never really compete: the chip is
    // offered before anything has been typed and the service drops it on the first keypress,
    // which is exactly when suggestions appear.
    val clip = state.clipSuggestion
    if (clip != null) {
        ClipboardStrip(
            height = StripHeight,
            item = clip,
            onOpen = { actions.setPage(KeyboardPage.CLIPBOARD) },
            onPaste = { actions.pasteClip(clip) },
            onDelete = { actions.deleteClip(clip) },
        )
        return
    }
    // The strip belongs to text entry only; numeric/phone/emoji pages never compose words,
    // so (like FUTO) they show no strip.
    val page = state.page
    val textPage = page == KeyboardPage.LETTERS ||
        page == KeyboardPage.SYMBOLS ||
        page == KeyboardPage.MORE_SYMBOLS
    if (!textPage) return
    // Nor do layouts that have nothing to put in it: the one dictionary we ship is English.
    val layout = state.settings.activeLayout
    when {
        // Candidates are not a suggestion the user can decline — on a Chinese layout they
        // are the only way a character gets typed — so the "show suggestions" preference
        // does not apply to them.
        layout.offersCandidates -> CandidateStrip(
            height = StripHeight,
            candidates = state.suggestions,
            onPick = actions::commitSuggestion,
        )
        layout.englishDictionary && state.settings.showSuggestions -> SuggestionStrip(
            height = StripHeight,
            suggestions = state.suggestions,
            onPick = actions::commitSuggestion,
        )
    }
}

/** The active page of keys. */
@Composable
private fun KeyPage(state: KeyboardState, actions: ImeActions, keyHeight: Dp) {
    when (state.page) {
        KeyboardPage.LETTERS -> LettersPage(state, actions, keyHeight)
        KeyboardPage.SYMBOLS ->
            SymbolPage(state, actions, keyHeight, Layouts.SYMBOL_ROWS, KeyboardPage.MORE_SYMBOLS, "=\\<")
        KeyboardPage.MORE_SYMBOLS ->
            SymbolPage(state, actions, keyHeight, Layouts.MORE_SYMBOL_ROWS, KeyboardPage.SYMBOLS, "?123")
        KeyboardPage.NUMERIC -> NumericPage(state, actions, keyHeight)
        KeyboardPage.PHONE -> PhonePage(state, actions, keyHeight)
        KeyboardPage.PHONE_SYMBOLS -> PhoneSymbolsPage(state, actions, keyHeight)
        KeyboardPage.EMOJI -> EmojiPage(
            data = state.emojiData,
            recents = state.recentEmoji,
            keyHeight = keyHeight,
            rows = 4,
            onEmoji = actions::commitEmoji,
            onSearch = actions::startEmojiSearch,
            onBackspace = actions::onBackspace,
            onBack = { actions.setPage(state.basePage) },
        )
        KeyboardPage.CLIPBOARD -> ClipboardPage(
            clips = state.clips,
            keyHeight = keyHeight,
            rows = 4,
            onPaste = actions::pasteClip,
            onDelete = actions::deleteClip,
            onClearAll = actions::clearClips,
            onBackspace = actions::onBackspace,
            onBack = { actions.setPage(state.basePage) },
        )
    }
}

/** Height of the strip above the keys, shared by everything that can occupy it. */
private val StripHeight = 44.dp

/**
 * The letter page for whichever layout is active. Rows differ in length between layouts
 * (QWERTY is 10/9/7, ЙЦУКЕН is 12/11/9), so short rows are centred inside the widest one
 * and the shift/backspace pair takes whatever the bottom row leaves over — which reproduces
 * QWERTY's familiar 0.5 spacers and 1.5-wide shift without hard-coding them.
 *
 * A layout may also have four rows (注音, JIS kana). Those take the digit row's place rather
 * than stacking on top of it, which is what the physical keyboards they come from do.
 */
@Composable
private fun LettersPage(state: KeyboardState, actions: ImeActions, keyHeight: Dp) {
    val layout = state.settings.activeLayout
    val rows = layout.rows
    if (state.settings.numberRow && rows.size < 4) {
        Row(Modifier.fillMaxWidth()) {
            DIGITS.forEach {
                SymbolKey(it, keyHeight, actions, alternates = Layouts.DIGIT_ALTERNATES[it].orEmpty())
            }
        }
    }
    // With no persistent number row, the top letter row doubles as one under a long press.
    // The two are mutually exclusive so a digit is never reachable two ways at once, and
    // layouts with four rows already spend that row on their own script.
    val topRowDigits = !state.settings.numberRow && rows.size < 4
    LetterRows(state, layout, actions, keyHeight, topRowDigits)
    // Email/URL fields surface @ or / where the comma usually sits.
    val commaChar = when (state.textVariation) {
        TextVariation.EMAIL -> "@"
        TextVariation.URL -> "/"
        TextVariation.NORMAL -> layout.comma
    }
    BottomRow(
        state, actions, keyHeight,
        leftLabel = "?123", leftTarget = KeyboardPage.SYMBOLS,
        commaChar = commaChar, periodChar = layout.period,
    )
}

/**
 * The letter rows proper, which are the only part of the page whose labels depend on shift.
 * Separated from the rest so that a shift change — which auto-capitalisation makes on most
 * keystrokes — redraws these and leaves the number row and the bottom row alone.
 */
@Composable
private fun LetterRows(
    state: KeyboardState,
    layout: KeyboardLayout,
    actions: ImeActions,
    keyHeight: Dp,
    topRowDigits: Boolean,
) {
    val rows = layout.rows
    val shift = state.shift
    val slack = { row: Int -> (layout.width - rows[row].length) / 2f }
    for (r in 0 until rows.size - 1) {
        Row(Modifier.fillMaxWidth()) {
            if (slack(r) > 0f) Spacer(Modifier.weight(slack(r)))
            rows[r].forEachIndexed { i, c ->
                LetterKey(
                    layout, r, i, c, shift, keyHeight, actions,
                    digit = if (topRowDigits && r == 0 && i < 10) DIGITS[i].toString() else null,
                )
            }
            if (slack(r) > 0f) Spacer(Modifier.weight(slack(r)))
        }
    }
    val bottom = rows.size - 1
    Row(Modifier.fillMaxWidth()) {
        val edge = slack(bottom).coerceAtLeast(1.25f)
        // Arabic, Hebrew and Persian have neither case nor a shift layer; the key would do
        // nothing, so the row keeps its alignment with a gap instead.
        if (layout.hasShift) {
            ShiftKey(keyHeight, edge, shift, actions::onShift)
        } else {
            Spacer(Modifier.weight(edge))
        }
        rows[bottom].forEachIndexed { i, c ->
            LetterKey(layout, bottom, i, c, shift, keyHeight, actions)
        }
        RepeatKey(keyHeight, edge, actions::onBackspace) { IconBackspace() }
    }
}

/**
 * One letter key. What shift produces is the layout's business — upper case for most, a
 * whole second character for Devanagari, Thai, Georgian and Turkish's dotted i — so the
 * label comes from [KeyboardLayout.charAt] rather than from `uppercaseChar()` here.
 *
 * [digit] is the number this key doubles as when there is no persistent number row. It goes
 * first in the alternates so it lands under the finger the moment the popup opens.
 */
@Composable
private fun RowScope.LetterKey(
    layout: KeyboardLayout,
    row: Int,
    col: Int,
    c: Char,
    shift: ShiftState,
    keyHeight: Dp,
    actions: ImeActions,
    digit: String? = null,
) {
    val shifted = shift != ShiftState.OFF
    val display = layout.charAt(row, col, shifted)
    // Per character, not String.uppercase(): that maps ß to "SS", which lengthens the
    // string and puts a stray S in the popup where the ß the user wanted used to be.
    val accents = layout.alternates[c].orEmpty()
        .let { if (shifted) it.map(Char::uppercaseChar).joinToString("") else it }
    CharKey(
        label = display,
        height = keyHeight,
        cornerHint = digit,
        alternates = digit.orEmpty() + accents,
        onClick = { actions.onChar(display) },
        onAlternate = actions::onChar,
    )
}

private const val DIGITS = "1234567890"

@Composable
private fun SymbolPage(
    state: KeyboardState,
    actions: ImeActions,
    keyHeight: Dp,
    rows: List<String>,
    otherPage: KeyboardPage,
    toggleLabel: String,
) {
    Row(Modifier.fillMaxWidth()) {
        rows[0].forEach { SymbolKey(it, keyHeight, actions, alternates = Layouts.alternatesFor(it)) }
    }
    Row(Modifier.fillMaxWidth()) {
        rows[1].forEach { SymbolKey(it, keyHeight, actions, alternates = Layouts.alternatesFor(it)) }
    }
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, 1.5f, onClick = { actions.setPage(otherPage) }) {
            Text(toggleLabel, fontSize = 14.sp)
        }
        rows[2].forEach { SymbolKey(it, keyHeight, actions, alternates = Layouts.alternatesFor(it)) }
        RepeatKey(keyHeight, 1.5f, actions::onBackspace) { IconBackspace() }
    }
    BottomRow(state, actions, keyHeight, leftLabel = "ABC", leftTarget = state.basePage)
}

@Composable
private fun RowScope.SymbolKey(
    c: Char,
    keyHeight: Dp,
    actions: ImeActions,
    weight: Float = 1f,
    alternates: String = "",
) {
    CharKey(
        label = c.toString(),
        height = keyHeight,
        weight = weight,
        alternates = alternates,
        onClick = { actions.onChar(c.toString()) },
        onAlternate = actions::onChar,
    )
}

/**
 * Numeric page, mirroring FUTO's `number.yaml`: functional operator columns flank three
 * digit columns, and the bottom row uses proportional widths so `0` lands centred under
 * the 2/5/8 column and every row shares the same left/right edges. Column widths are
 * FUTO's fractions — side keys 0.15, digits 0.2333, comma/period 0.1, the two "grow"
 * keys splitting the remainder — used directly as (relative) row weights.
 */
@Composable
private fun NumericPage(state: KeyboardState, actions: ImeActions, keyHeight: Dp) {
    val side = 0.15f
    val digit = 0.2333f
    val reg = 0.1f
    val grow = 0.1333f
    Row(Modifier.fillMaxWidth()) {
        NumFunctionKey("+", side, keyHeight, actions)
        SymbolKey('1', keyHeight, actions, digit)
        SymbolKey('2', keyHeight, actions, digit)
        SymbolKey('3', keyHeight, actions, digit)
        NumFunctionKey("%", side, keyHeight, actions)
    }
    Row(Modifier.fillMaxWidth()) {
        NumFunctionKey("-", side, keyHeight, actions)
        SymbolKey('4', keyHeight, actions, digit)
        SymbolKey('5', keyHeight, actions, digit)
        SymbolKey('6', keyHeight, actions, digit)
        SpecialKey(keyHeight, side, onClick = actions::onSpace) { IconSpaceBar() }
    }
    Row(Modifier.fillMaxWidth()) {
        NumFunctionKey("*", side, keyHeight, actions)
        SymbolKey('7', keyHeight, actions, digit)
        SymbolKey('8', keyHeight, actions, digit)
        SymbolKey('9', keyHeight, actions, digit)
        RepeatKey(keyHeight, side, actions::onBackspace) { IconBackspace() }
    }
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, side, onClick = { actions.setPage(KeyboardPage.LETTERS) }) {
            Text(stringResource(R.string.abc), fontSize = 14.sp)
        }
        NumFunctionKey(",", reg, keyHeight, actions)
        SpecialKey(keyHeight, grow, onClick = { actions.setPage(KeyboardPage.SYMBOLS) }) {
            Text("?123", fontSize = 14.sp)
        }
        SymbolKey('0', keyHeight, actions, digit)
        NumFunctionKey("=", grow, keyHeight, actions)
        NumFunctionKey(".", reg, keyHeight, actions)
        EnterKey(state, actions, keyHeight, side)
    }
}

/** A character key with functional (dimmer) styling — used for the numeric page operators. */
@Composable
private fun RowScope.NumFunctionKey(label: String, weight: Float, keyHeight: Dp, actions: ImeActions) {
    SpecialKey(keyHeight, weight, onClick = { actions.onChar(label) }) {
        Text(label, fontSize = 20.sp)
    }
}

/**
 * Phone dial-pad, mirroring FUTO's `phone.yaml`: an even 4-column grid with the digits
 * carrying their ABC/DEF letter hints, functional keys (−, space, ⌫) down the right edge,
 * and a toggle to the phone-symbols page. The enter key reflects the field's IME action.
 */
@Composable
private fun PhonePage(state: KeyboardState, actions: ImeActions, keyHeight: Dp) {
    Row(Modifier.fillMaxWidth()) {
        PhoneKey('1', null, keyHeight, actions)
        PhoneKey('2', "ABC", keyHeight, actions)
        PhoneKey('3', "DEF", keyHeight, actions)
        NumFunctionKey("-", 1f, keyHeight, actions)
    }
    Row(Modifier.fillMaxWidth()) {
        PhoneKey('4', "GHI", keyHeight, actions)
        PhoneKey('5', "JKL", keyHeight, actions)
        PhoneKey('6', "MNO", keyHeight, actions)
        SpecialKey(keyHeight, 1f, onClick = actions::onSpace) { IconSpaceBar() }
    }
    Row(Modifier.fillMaxWidth()) {
        PhoneKey('7', "PQRS", keyHeight, actions)
        PhoneKey('8', "TUV", keyHeight, actions)
        PhoneKey('9', "WXYZ", keyHeight, actions)
        RepeatKey(keyHeight, 1f, actions::onBackspace) { IconBackspace() }
    }
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, 1f, onClick = { actions.setPage(KeyboardPage.PHONE_SYMBOLS) }) {
            Text("*#(", fontSize = 16.sp)
        }
        PhoneKey('0', "+", keyHeight, actions)
        CharKey(".", keyHeight, 1f, onClick = { actions.onChar(".") })
        EnterKey(state, actions, keyHeight, 1f)
    }
}

/** A phone-page digit with its dial-pad letter hint (matches FUTO's phone layout). */
@Composable
private fun RowScope.PhoneKey(c: Char, hint: String?, keyHeight: Dp, actions: ImeActions) {
    CharKey(label = c.toString(), height = keyHeight, hint = hint, onClick = { actions.onChar(c.toString()) })
}

/**
 * Phone symbols page, mirroring FUTO's `phone_shift.yaml`: brackets/slash, the dialer
 * pause (`,`) and wait (`;`) keys, `* # +`, and a toggle back to the dial pad.
 */
@Composable
private fun PhoneSymbolsPage(state: KeyboardState, actions: ImeActions, keyHeight: Dp) {
    Row(Modifier.fillMaxWidth()) {
        SymbolKey('(', keyHeight, actions)
        SymbolKey('/', keyHeight, actions)
        SymbolKey(')', keyHeight, actions)
        NumFunctionKey("-", 1f, keyHeight, actions)
    }
    Row(Modifier.fillMaxWidth()) {
        SymbolKey('N', keyHeight, actions)
        PhoneWordKey("Pause", ",", keyHeight, actions)
        SymbolKey(',', keyHeight, actions)
        SpecialKey(keyHeight, 1f, onClick = actions::onSpace) { IconSpaceBar() }
    }
    Row(Modifier.fillMaxWidth()) {
        SymbolKey('*', keyHeight, actions)
        PhoneWordKey("Wait", ";", keyHeight, actions)
        SymbolKey('#', keyHeight, actions)
        RepeatKey(keyHeight, 1f, actions::onBackspace) { IconBackspace() }
    }
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, 1f, onClick = { actions.setPage(KeyboardPage.PHONE) }) {
            Text("123", fontSize = 14.sp)
        }
        SymbolKey('+', keyHeight, actions)
        SymbolKey('.', keyHeight, actions)
        EnterKey(state, actions, keyHeight, 1f)
    }
}

/**
 * A word-labelled key (Pause/Wait) that commits the dialer control char. Rendered like a
 * normal (raised) key but with small text so the word fits.
 */
@Composable
private fun RowScope.PhoneWordKey(label: String, commit: String, keyHeight: Dp, actions: ImeActions) {
    SpecialKey(keyHeight, 1f, containerColor = charKeyColor(), onClick = { actions.onChar(commit) }) {
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun BottomRow(
    state: KeyboardState,
    actions: ImeActions,
    keyHeight: Dp,
    leftLabel: String,
    leftTarget: KeyboardPage,
    commaChar: String = ",",
    periodChar: String = ".",
) {
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, 1.5f, onClick = { actions.setPage(leftTarget) }) {
            Text(leftLabel, fontSize = 14.sp)
        }
        SpecialKey(keyHeight, 1f, onClick = { actions.setPage(KeyboardPage.EMOJI) }) { IconEmoji() }
        if (state.settings.clipboardEnabled) {
            SpecialKey(keyHeight, 1f, onClick = { actions.setPage(KeyboardPage.CLIPBOARD) }) { IconPaste() }
        }
        CharKey(
            label = commaChar,
            height = keyHeight,
            weight = 1f,
            alternates = punctuationAlternates(commaChar),
            onClick = { actions.onChar(commaChar) },
            onAlternate = actions::onChar,
        )
        SpaceKey(
            height = keyHeight,
            weight = 4f,
            label = state.settings.activeLayout.name,
            onSpace = actions::onSpace,
        )
        CharKey(
            label = periodChar,
            height = keyHeight,
            weight = 1f,
            alternates = punctuationAlternates(periodChar),
            onClick = { actions.onChar(periodChar) },
            onAlternate = actions::onChar,
        )
        EnterKey(state, actions, keyHeight, 1.5f)
    }
}

/**
 * Alternates for the two punctuation keys beside the space bar. A layout may put a
 * multi-character string there (or the field flavour may swap in `@`/`/`), and only a
 * single character can have alternates.
 */
private fun punctuationAlternates(label: String): String =
    label.singleOrNull()?.let { Layouts.SYMBOL_ALTERNATES[it] }.orEmpty()

@Composable
private fun RowScope.EnterKey(state: KeyboardState, actions: ImeActions, keyHeight: Dp, weight: Float) {
    SpecialKey(
        height = keyHeight,
        weight = weight,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        onClick = actions::onEnter,
    ) {
        // A custom action carries an app-supplied label ("Join", "Post", ...) whose purpose
        // we can't map to an icon, so that one case stays textual; every standard action
        // gets a glyph.
        val customLabel = state.enterActionLabel
        if (customLabel != null) {
            Text(customLabel, fontSize = 14.sp, maxLines = 1)
        } else {
            EnterActionIcon(state.enterAction)
        }
    }
}

/**
 * The glyph for each Enter purpose. Arrow/send/return icons are AutoMirrored so they flip
 * in RTL layouts; the skip pair used for field navigation is deliberately directional in
 * the media sense and does not mirror.
 */
@Composable
private fun EnterActionIcon(action: EnterAction) = when (action) {
    EnterAction.SEARCH -> IconSearch()
    EnterAction.SEND -> IconSend()
    EnterAction.DONE -> IconCheck()
    EnterAction.GO -> IconArrowForward()
    EnterAction.NEXT -> IconSkipNext()
    EnterAction.PREVIOUS -> IconSkipPrevious()
    EnterAction.RETURN -> IconReturn()
}
