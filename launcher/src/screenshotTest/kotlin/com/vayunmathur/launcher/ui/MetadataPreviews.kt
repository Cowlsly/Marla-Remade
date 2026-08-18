package com.vayunmathur.launcher.ui

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.ComponentKey
import com.vayunmathur.launcher.platform.DrawerActions
import com.vayunmathur.launcher.platform.DrawerApp
import com.vayunmathur.launcher.platform.DrawerUiState
import com.vayunmathur.launcher.platform.FolderActions
import com.vayunmathur.launcher.platform.HomeActions
import com.vayunmathur.launcher.platform.HomeUiState
import com.vayunmathur.launcher.platform.SettingsActions
import com.vayunmathur.launcher.platform.SettingsUiState
import com.vayunmathur.launcher.platform.WidgetEntry
import com.vayunmathur.launcher.platform.WidgetGroup
import com.vayunmathur.launcher.platform.WidgetPickerActions
import com.vayunmathur.launcher.platform.WidgetPickerUiState
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Surface

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:launcher`, rendered from Compose previews rather than from an
 * instrumented test on a device.
 *
 * `./gradlew :launcher:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/launcher/`, where `release.sh` picks them up.
 *
 * Three things to keep in mind when editing:
 *
 *  - Order comes from the function names, which are embedded in the generated filenames.
 *    Renumber if the listing order changes.
 *  - Everything must be a literal. There is no ViewModel, no database and no device here,
 *    which is also what makes the output reproducible from a clean checkout. It is why the UI
 *    state models carry a `ComponentKey` rather than a `UserHandle`.
 *  - Each preview needs `@PreviewTest` as well as `@Preview`, and they must be members of a
 *    class. `@Preview` alone renders in Studio but is not collected, and top-level previews
 *    land in a synthetic facade the screenshot engine skips — both surface as the unhelpful
 *    "did not discover any tests".
 *
 * App icons are absent on purpose: `LocalIconLoader` defaults to `IconLoader.Noop` here,
 * because there are no installed apps to load artwork from, so every icon draws its
 * placeholder.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-home", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Home() {
        DynamicTheme(darkTheme = true) {
            WithDrag {
                HomeContent(state = homeState(), actions = HomeActions.Noop)
            }
        }
    }

    @PreviewTest
    @Preview(name = "2-drawer", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Drawer() {
        DynamicTheme(darkTheme = true) {
            WithDrag {
                // The drawer is an overlay on the home screen, not a destination, so it is shown
                // the way it really appears - over the workspace.
                HomeContent(
                    state = homeState(),
                    actions = HomeActions.Noop,
                    drawerState = DrawerUiState(
                        apps = listOf(
                            "Calendar", "Camera", "Clock", "Contacts", "Email", "Files",
                            "Maps", "Measure", "Music", "Notes", "Passwords", "Photos",
                            "Translate", "Weather", "Web",
                        ).mapIndexed { index, label -> drawerApp(label, index) },
                    ),
                    drawerActions = DrawerActions.Noop,
                    initialDrawerOpen = true,
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "3-folder", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Folder() {
        DynamicTheme(darkTheme = true) {
            WithDrag {
                HomeContent(
                    state = homeState(),
                    actions = HomeActions.Noop,
                    folderActions = FolderActions.Noop,
                    initialOpenFolderId = FOLDER_ID,
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "4-widgets", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Widgets() {
        DynamicTheme(darkTheme = true) {
            Surface {
                WidgetPickerContent(
                    state = WidgetPickerUiState(
                        groups = listOf(
                            WidgetGroup(
                                "Clock",
                                listOf(
                                    WidgetEntry("com.vayunmathur.clock/.Widget", "Alarms", "Next alarm", 2, 1),
                                    WidgetEntry("com.vayunmathur.clock/.Digital", "Digital clock", "", 4, 2),
                                ),
                            ),
                            WidgetGroup(
                                "Weather",
                                listOf(
                                    WidgetEntry("com.vayunmathur.weather/.Widget", "Forecast", "Five days", 4, 2),
                                ),
                            ),
                            WidgetGroup(
                                "Notes",
                                listOf(
                                    WidgetEntry("com.vayunmathur.notes/.Widget", "Recent notes", "", 2, 2),
                                ),
                            ),
                        ),
                    ),
                    actions = WidgetPickerActions.Noop,
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "5-settings", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview5Settings() {
        DynamicTheme(darkTheme = true) {
            SettingsContent(
                state = SettingsUiState(isDefaultHome = true),
                actions = SettingsActions.Noop,
            )
        }
    }

    /**
     * The home screen reads its drag state from a composition local with no default, since
     * every real entry point provides one. A preview has to stand one up itself.
     */
    @Composable
    private fun WithDrag(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) { content() }
    }

    private fun homeState() = HomeUiState(
        loading = false,
        grid = GridSpec(columns = 5, rows = 5, hotseatSlots = 5),
        pages = mapOf(
            0 to listOf(
                item(1, "Calendar", CellRect(0, 0)),
                item(2, "Camera", CellRect(1, 0)),
                item(3, "Clock", CellRect(2, 0)),
                item(4, "Notes", CellRect(3, 0)),
                item(5, "Weather", CellRect(4, 0)),
                WorkspaceItem(
                    id = 6,
                    type = LauncherItemType.APPWIDGET,
                    label = "Forecast",
                    rect = CellRect(0, 1, 4, 2),
                    appWidgetProvider = "com.vayunmathur.weather/.Widget",
                ),
                WorkspaceItem(
                    id = FOLDER_ID,
                    type = LauncherItemType.FOLDER,
                    label = "Media",
                    rect = CellRect(0, 3),
                    children = listOf(
                        item(70, "Music", CellRect(0, 0)),
                        item(71, "Photos", CellRect(1, 0)),
                        item(72, "Videos", CellRect(2, 0)),
                        item(73, "Podcasts", CellRect(3, 0)),
                    ).map { it.copy(container = ContainerRef.Folder(FOLDER_ID)) },
                ),
                item(8, "Files", CellRect(1, 3)),
                item(9, "Maps", CellRect(2, 3)),
            ),
            // A second page, so the indicator has something to indicate. The trailing empty page
            // only exists while something is being dragged, so a one-page workspace has no dots.
            1 to listOf(
                item(10, "Music", CellRect(0, 0)).copy(screen = 1),
                item(11, "Podcasts", CellRect(1, 0)).copy(screen = 1),
            ),
        ),
        hotseat = listOf(
            item(20, "Phone", CellRect(0, 0)).copy(container = ContainerRef.Hotseat, rank = 0),
            item(21, "Messages", CellRect(1, 0)).copy(container = ContainerRef.Hotseat, rank = 1),
            item(22, "Web", CellRect(2, 0)).copy(container = ContainerRef.Hotseat, rank = 2),
            item(23, "Camera", CellRect(3, 0)).copy(container = ContainerRef.Hotseat, rank = 3),
            item(24, "Email", CellRect(4, 0)).copy(container = ContainerRef.Hotseat, rank = 4),
        ),
    )

    private fun item(id: Long, label: String, rect: CellRect) = WorkspaceItem(
        id = id,
        type = LauncherItemType.APPLICATION,
        label = label,
        rect = rect,
        key = componentKey(label),
    )

    private fun drawerApp(label: String, index: Int) = DrawerApp(
        key = componentKey(label),
        label = label,
        isWorkProfile = index == 4,
    )

    private fun componentKey(label: String) = ComponentKey(
        ComponentName("com.example.${label.lowercase()}", ".MainActivity"),
        profileSerial = 0,
    )

    private companion object {
        /** The folder the folder preview opens. */
        const val FOLDER_ID = 7L
    }
}
