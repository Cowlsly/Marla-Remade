package com.vayunmathur.passwords.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.platform.MenuUiState
import com.vayunmathur.passwords.platform.PasswordEditUiState
import com.vayunmathur.passwords.platform.PasswordUiState
import com.vayunmathur.passwords.platform.PasswordsActions

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * A fixed instant instead of `System.currentTimeMillis()`, so the TOTP codes and the
 * countdown ring come out byte-identical on every run. This one lands 10 seconds before
 * the end of a 30-second step, which is what makes the ring render two-thirds spent.
 */
private const val NOW = 1_700_000_000_000L

// Sample vault contents. Everything here is deliberately, visibly fake — example.com
// domains and "Sample"/"Demo" names — because these images end up on a public store page
// for a password manager. The TOTP secrets are valid base32 only so that TOTP.generate
// produces a plausible six-digit code rather than throwing mid-render.
private val SampleMail = Password(
    id = 1,
    name = "Example Mail",
    email = "sample.user@example.com",
    password = "sample-passphrase",
    totpSecret = "JBSWY3DPEHPK3PXP",
    websites = listOf("mail.example.com", "login.example.com"),
)

private val SampleBank = Password(
    id = 2,
    name = "Sample Bank",
    username = "sampleuser",
    email = "sample.user@example.com",
    password = "another-sample-value",
    totpSecret = "KRSXG5CTMVRXEZLU",
    note = "Recovery codes: 1234-5678, 9012-3456",
    websites = listOf("bank.example.com"),
)

private val SampleShop = Password(
    id = 3,
    name = "Demo Shop",
    email = "demo@example.org",
    password = "demo-only-value",
    websites = listOf("shop.example.org"),
)

private val SampleRouter = Password(
    id = 4,
    name = "Sample Router",
    username = "admin",
    password = "not-a-real-password",
)

private val SamplePasskey = Passkey(
    id = 1,
    rpId = "example.com",
    rpName = "Example",
    credentialId = "c2FtcGxlLWNyZWRlbnRpYWw",
    userId = "sample-user",
    userName = "sample.user@example.com",
    userDisplayName = "Sample User",
)

/**
 * Store listing images for `:passwords`. See `common-conventions-preview-metadata`.
 *
 * These render the three screens from the literal sample vault above; no KDBX file is
 * opened and no database exists, which is the point — the previous generator needed a real
 * unlocked vault on a device to take these.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-vault", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Vault() {
        DynamicTheme(darkTheme = true) {
            MenuScreen(
                backStack = rememberNavBackStack<Route>(Route.Menu),
                state = MenuUiState(
                    passwords = listOf(SampleMail, SampleBank, SampleShop, SampleRouter),
                    passkeys = listOf(SamplePasskey),
                    now = NOW,
                ),
                actions = PasswordsActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-entry", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Entry() {
        DynamicTheme(darkTheme = true) {
            PasswordScreen(
                state = PasswordUiState(password = SampleMail, now = NOW),
                actions = PasswordsActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-edit", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Edit() {
        DynamicTheme(darkTheme = true) {
            PasswordEditScreen(
                state = PasswordEditUiState(saved = SampleBank, draft = SampleBank),
                actions = PasswordsActions.Noop,
            )
        }
    }
}
