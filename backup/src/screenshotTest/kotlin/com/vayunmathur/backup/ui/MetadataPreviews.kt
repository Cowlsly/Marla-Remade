package com.vayunmathur.backup.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.backup.data.BackendType
import com.vayunmathur.backup.data.BackupSettings
import com.vayunmathur.backup.platform.BackupUiState
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store-listing images for `:backup`, rendered from Compose previews instead of an
 * instrumented test on a device (the privileged transport needs a platform-signed
 * install and cannot run here).
 *
 * `./gradlew :backup:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/backup/`, where `release.sh` picks them up.
 *
 * Each preview must carry @PreviewTest as well as @Preview and be a member of a class
 * (not a top-level function) or the screenshot engine silently skips it. Everything is
 * a literal so the output is reproducible from a clean checkout.
 */
class MetadataPreviews {

    private val configured = BackupSettings(
        backendType = BackendType.SAF,
        safTreeUri = "content://com.android.externalstorage.documents/tree/primary:Backups",
        appBackupEnabled = true,
        fileBackupEnabled = true,
        lastRun = 1_786_500_000_000L,
    )

    @PreviewTest
    @Preview(name = "1-dashboard", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Dashboard() {
        DynamicTheme(darkTheme = true) {
            DashboardScreen(
                state = BackupUiState(settings = configured, hasKey = true),
                onPickFolder = {},
                onSetWebDav = { _, _, _ -> },
                onAppBackupToggle = {},
                onFileBackupToggle = {},
                onBackupNow = {},
                onRestoreNow = {},
                onDismissMessages = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-recovery-code", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Onboarding() {
        DynamicTheme(darkTheme = true) {
            OnboardingScreen(
                state = BackupUiState(
                    generatedCode = listOf(
                        "abandon", "ability", "able", "about", "above", "absent",
                        "absorb", "abstract", "absurd", "abuse", "access", "accident",
                    ),
                ),
                onPickFolder = {},
                onSetWebDav = { _, _, _ -> },
                onGenerate = {},
                onConfirmNew = {},
                onRestoreWithCode = {},
                onDismissMessages = {},
            )
        }
    }
}
