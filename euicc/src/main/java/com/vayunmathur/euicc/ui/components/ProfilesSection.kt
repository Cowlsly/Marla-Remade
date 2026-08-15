package com.vayunmathur.euicc.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.euicc.data.Profile
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.OverflowMenu
import com.vayunmathur.library.ui.Text

@Composable
fun ProfilesSection(
    profiles: List<Profile>,
    onEnable: (Profile) -> Unit,
    onDisable: (Profile) -> Unit,
    onRename: (Profile) -> Unit,
    onDelete: (Profile) -> Unit,
) {
    SectionCard(title = "Profiles") {
        if (profiles.isEmpty()) {
            Text("No profiles installed.")
            return@SectionCard
        }
        for (profile in profiles) {
            ListItem(
                headlineContent = { Text(profile.displayName) },
                supportingContent = {
                    Text(
                        (if (profile.isEnabled) "Enabled" else "Disabled") + " \u00b7 " + profile.iccidDisplay,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    OverflowMenu {
                        if (profile.isEnabled) {
                            Item("Disable") { onDisable(profile) }
                        } else {
                            Item("Enable") { onEnable(profile) }
                        }
                        Item("Rename") { onRename(profile) }
                        Item("Delete") { onDelete(profile) }
                    }
                },
            )
        }
    }
}
