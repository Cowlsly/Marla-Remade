package com.vayunmathur.translate

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.translate.platform.TranslateViewModel
import com.vayunmathur.translate.ui.CameraTranslateScreen
import com.vayunmathur.translate.ui.LanguagePickerPage
import com.vayunmathur.translate.ui.TextTranslatePage

@Composable
fun Navigation(viewModel: TranslateViewModel, initialText: String) {
    val backStack = rememberNavBackStack<Route>(Route.Text)
    MainNavigation(backStack) {
        entry<Route.Text> {
            TextTranslatePage(
                viewModel = viewModel,
                initialText = initialText,
                onOpenCamera = { backStack.add(Route.Camera) },
                onOpenLanguagePicker = { forSource ->
                    backStack.add(Route.LanguagePicker(forSource))
                },
            )
        }
        entry<Route.Camera>(metadata = FullscreenPage()) {
            CameraTranslateScreen(
                viewModel = viewModel,
                onBack = { backStack.pop() },
                onOpenLanguagePicker = { forSource ->
                    backStack.add(Route.LanguagePicker(forSource))
                },
            )
        }
        entry<Route.LanguagePicker> { route ->
            LanguagePickerPage(
                viewModel = viewModel,
                forSource = route.forSource,
                onBack = { backStack.pop() },
            )
        }
    }
}
