package com.vayunmathur.findfamily.ui.dialogs
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.findfamily.util.FindFamilyViewModel
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.findfamily.util.Platform
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.library.ui.IconCopy
import kotlin.time.Clock

@Composable
fun AddPersonDialog(
    backStack: NavBackStack<Route>,
    ffViewModel: FindFamilyViewModel,
    platform: Platform,
    id: Long?,
) {
    val usersByID by ffViewModel.usersById.collectAsState()

    var userid: String by remember { mutableStateOf(id?.encodeBase26() ?: "") }
    var contactName: String? by remember { mutableStateOf(null) }
    var contactPhoto by remember { mutableStateOf<String?>(null) }
    val requestPickContact2 = platform.requestPickContact { name, photo ->
        contactName = name
        contactPhoto = photo
    }

    val userStatus = usersByID[userid.decodeBase26()]?.requestStatus
    val context = LocalContext.current
    var showOutdatedPeer by remember { mutableStateOf(false) }

    Dialog({backStack.pop()}) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.add_person_title), style = MaterialTheme.typography.headlineMedium)

                OutlinedTextField(
                    Networking.userid.encodeBase26(),
                    {},
                    interactionSource = interactionSourceClickable {
                        platform.copy(Networking.userid.encodeBase26())
                    },
                    label = { Text(stringResource(R.string.your_findfamily_id)) },
                    trailingIcon = {
                        IconCopy()
                    },
                    readOnly = true
                )

                // Share sheet instead of copy/paste: sends an https link that bounces to
                // findfamily://add/<myId>, which prefills the recipient's Add Person dialog.
                // The web hop exists because many messengers won't linkify a bare custom
                // scheme. The id rides in the fragment, which browsers never send, so the
                // server sees only that some share page was opened.
                OutlinedButton(
                    {
                        val link = "https://ma.vayunmathur.com/ff/share#id=${Networking.userid.encodeBase26()}"
                        ExternalIntents.shareText(
                            context,
                            link,
                            context.getString(R.string.share_invite_link_chooser),
                        )
                    },
                    Modifier.fillMaxWidth()
                ) {
                    IconShare()
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share_invite_link))
                }

                OutlinedTextField(
                    userid,
                    { userid = it },
                    readOnly = id != null,
                    label = { Text(stringResource(R.string.contact_findfamily_id)) },
                    isError = userStatus == RequestStatus.MUTUAL_CONNECTION || userStatus == RequestStatus.AWAITING_RESPONSE,
                    supportingText =
                        when (userStatus) {
                            RequestStatus.AWAITING_REQUEST -> { @Composable {Text(stringResource(R.string.status_awaiting_request))}}
                            RequestStatus.MUTUAL_CONNECTION -> {
                                if (userid == Networking.userid.encodeBase26())
                                    {@Composable {Text(stringResource(R.string.status_cannot_share_self))}}
                                else {@Composable {Text(stringResource(R.string.status_already_sharing))}}
                            }
                            RequestStatus.AWAITING_RESPONSE -> {@Composable {Text(stringResource(R.string.status_already_requested)) } }
                            else -> null
                        }
                    )

                OutlinedTextField(contactName ?: "", {}, interactionSource = interactionSourceClickable {
                    requestPickContact2()
                }, label = { Text(stringResource(R.string.contact_name_label)) }, readOnly = true)

                Button(
                    {
                        val userToAdd = User(
                            contactName!!,
                            contactPhoto,
                            "Unknown Location",
                            true,
                            if (userStatus == RequestStatus.AWAITING_REQUEST) RequestStatus.MUTUAL_CONNECTION else RequestStatus.AWAITING_RESPONSE,
                            Clock.System.now(),
                            null,
                            userid.decodeBase26()
                        )
                        ffViewModel.connectUser(
                            userToAdd,
                            onNeedsUpdate = { showOutdatedPeer = true },
                            onDone = { backStack.pop() },
                        )
                    },
                    enabled = userid.isNotBlank() && contactName != null && !(userStatus == RequestStatus.MUTUAL_CONNECTION || userStatus == RequestStatus.AWAITING_RESPONSE)
                ) {
                    if (userStatus == RequestStatus.AWAITING_REQUEST) {
                        Text(stringResource(R.string.accept_location_request))
                    } else {
                        Text(stringResource(R.string.request_location))
                    }
                }
            }
        }
    }

    if (showOutdatedPeer) {
        OutdatedPeerDialog(onDismiss = { showOutdatedPeer = false })
    }
}

fun String.decodeBase26(): Long = fold(0uL) { acc, c ->
    acc * 26uL + (c.code - 65).toULong()
}.toLong()

fun Long.encodeBase26(): String = buildString {
    var remaining = this@encodeBase26.toULong()
    while (remaining > 0uL) {
        insert(0, ((remaining % 26uL) + 65uL).toInt().toChar())
        remaining /= 26uL
    }
}

@Composable
fun interactionSourceClickable(onClick: () -> Unit): MutableInteractionSource {
    return remember { MutableInteractionSource() }
        .also { interactionSource ->
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collect {
                    if (it is PressInteraction.Release) {
                        onClick()
                    }
                }
            }
        }
}
