package com.vayunmathur.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.library.ui.DynamicTheme
import java.io.File

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

private const val ROOT = "/storage/emulated/0"

/**
 * Store listing images for `:files`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * The [File]s below are never touched — the browser only reads the names and sizes it is
 * handed — so these render without a filesystem, a device, or the all-files permission the
 * real app needs before it shows anything at all.
 */
class MetadataPreviews {

    private fun dir(path: String) = FileBrowserItem(
        name = path.substringAfterLast('/'),
        isDirectory = true,
        size = null,
        realFile = File(path),
        zipInnerPath = null,
        key = path,
    )

    private fun file(path: String, size: Long) = FileBrowserItem(
        name = path.substringAfterLast('/'),
        isDirectory = false,
        size = size,
        realFile = File(path),
        zipInnerPath = null,
        key = path,
    )

    private fun zipDir(inner: String) = FileBrowserItem(
        name = inner.substringAfterLast('/'),
        isDirectory = true,
        size = null,
        realFile = null,
        zipInnerPath = inner,
        key = "zip:$inner",
    )

    private fun zipFile(inner: String, size: Long) = FileBrowserItem(
        name = inner.substringAfterLast('/'),
        isDirectory = false,
        size = size,
        realFile = null,
        zipInnerPath = inner,
        key = "zip:$inner",
    )

    @PreviewTest
    @Preview(name = "1-browse", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Browse() {
        DynamicTheme(darkTheme = true) {
            DirectoryScreen(
                state = FilesUiState(
                    rootDirectory = File(ROOT),
                    rootDisplayName = "Pixel 9 Pro",
                    currentDirectory = File(ROOT),
                    directories = listOf(
                        dir("$ROOT/DCIM"),
                        dir("$ROOT/Documents"),
                        dir("$ROOT/Download"),
                        dir("$ROOT/Movies"),
                        dir("$ROOT/Music"),
                        dir("$ROOT/Pictures"),
                    ),
                    files = listOf(
                        file("$ROOT/backup-2026-07.zip", 48_301_772),
                        file("$ROOT/reading-list.md", 12_408),
                    ),
                ),
                actions = FilesActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-selection", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Selection() {
        val downloads = "$ROOT/Download"
        val receipts = file("$downloads/receipts-june.pdf", 184_320)
        val slides = file("$downloads/offsite-slides.pdf", 2_517_912)
        DynamicTheme(darkTheme = true) {
            DirectoryScreen(
                state = FilesUiState(
                    rootDirectory = File(ROOT),
                    rootDisplayName = "Pixel 9 Pro",
                    currentDirectory = File(downloads),
                    directories = listOf(dir("$downloads/invoices")),
                    files = listOf(
                        file("$downloads/IMG_2481.jpg", 3_145_728),
                        slides,
                        receipts,
                        file("$downloads/trip-notes.txt", 4_096),
                    ),
                    selectedPaths = setOf(slides, receipts),
                ),
                actions = FilesActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-zip", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Zip() {
        DynamicTheme(darkTheme = true) {
            DirectoryScreen(
                state = FilesUiState(
                    rootDirectory = File(ROOT),
                    rootDisplayName = "Pixel 9 Pro",
                    // Zip mode ignores the current directory; the breadcrumb comes from the
                    // archive's own path, exactly as the ViewModel leaves it.
                    currentDirectory = File("/"),
                    zipPath = File("$ROOT/Download/backup-2026-07.zip"),
                    directories = listOf(zipDir("photos"), zipDir("receipts")),
                    files = listOf(
                        zipFile("manifest.json", 2_048),
                        zipFile("notes.md", 9_512),
                        zipFile("settings.xml", 1_204),
                    ),
                ),
                actions = FilesActions.Noop,
            )
        }
    }
}
