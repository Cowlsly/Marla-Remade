package com.vayunmathur.translate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.platform.LanguagePickerActions
import com.vayunmathur.translate.platform.LanguagePickerUiState
import com.vayunmathur.translate.platform.TranslateViewModel

/**
 * Binds [TranslateViewModel] to the stateless [LanguagePickerScreen].
 *
 * Everything a `@Preview` cannot supply lives here: the collected language
 * selection and recents, plus the search query held in `remember`. Selecting a
 * row persists through the ViewModel (which also records the recent) and pops
 * back; the caller's existing translation effect re-runs on the new language.
 */
@Composable
fun LanguagePickerPage(
    viewModel: TranslateViewModel,
    forSource: Boolean,
    onBack: () -> Unit,
) {
    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val recentSourceCodes by viewModel.recentSourceLangs.collectAsState()
    val recentTargetCodes by viewModel.recentTargetLangs.collectAsState()

    var query by remember { mutableStateOf("") }

    // Stale codes (removed from Languages.ALL since they were stored) resolve
    // to nothing rather than to the English fallback byCode would give.
    val recents = remember(recentSourceCodes, recentTargetCodes, forSource) {
        val codes = if (forSource) recentSourceCodes else recentTargetCodes
        codes.mapNotNull { code -> Languages.ALL.firstOrNull { it.code == code } }
    }

    LanguagePickerScreen(
        state = LanguagePickerUiState(
            forSource = forSource,
            selectedCode = if (forSource) sourceLang else targetLang,
            recents = recents,
            query = query,
        ),
        actions = object : LanguagePickerActions {
            override fun select(code: String) {
                if (forSource) viewModel.setSource(code) else viewModel.setTarget(code)
                onBack()
            }

            override fun setQuery(text: String) {
                query = text
            }

            override fun goBack() = onBack()
        },
    )
}
