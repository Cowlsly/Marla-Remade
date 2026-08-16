package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.SimManager
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberPermissionRequest
import java.util.Date

@Composable
fun PermissionGate(
    permission: String,
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val context = LocalContext.current
    var grantRevision by remember { mutableStateOf(0) }
    val requestPermission = rememberPermissionRequest(permission) { grantRevision++ }
    val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    if (granted) {
        content(grantRevision)
    } else {
        EmptyState(
            title = stringResource(R.string.permission_title),
            message = message,
            icon = { IconCall() },
            action = {
                Button(onClick = requestPermission) {
                    Text(stringResource(R.string.grant_permission))
                }
            },
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
fun RoleGate(
    roleName: String,
    message: String,
    actionLabel: String,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    content: @Composable (Int) -> Unit,
) {
    val context = LocalContext.current
    val roleManager = remember(context) { context.getSystemService(RoleManager::class.java)!! }
    var roleRevision by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { roleRevision++ }
    val available = roleManager.isRoleAvailable(roleName)
    val held = roleManager.isRoleHeld(roleName)

    if (held) {
        content(roleRevision)
    } else {
        EmptyState(
            title = stringResource(R.string.default_app_title),
            message = if (available) message else stringResource(R.string.default_role_unavailable),
            icon = icon,
            action = if (available) {
                {
                    Button(onClick = { launcher.launch(roleManager.createRequestRoleIntent(roleName)) }) {
                        Text(actionLabel)
                    }
                }
            } else {
                null
            },
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
fun DefaultSmsGate(
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    RoleGate(
        roleName = RoleManager.ROLE_SMS,
        message = stringResource(R.string.default_sms_message),
        actionLabel = stringResource(R.string.become_default_sms),
        modifier = modifier,
        icon = { IconSms() },
        content = content,
    )
}

@Composable
fun DefaultDialerGate(
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    RoleGate(
        roleName = RoleManager.ROLE_DIALER,
        message = stringResource(R.string.default_dialer_message),
        actionLabel = stringResource(R.string.become_default_dialer),
        modifier = modifier,
        icon = { IconCall() },
        content = content,
    )
}

fun Context.hasCommunicatePermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.hasContactsPermission(): Boolean = hasCommunicatePermission(Manifest.permission.READ_CONTACTS)

fun formatDateTime(context: Context, timestampMillis: Long): String {
    val now = System.currentTimeMillis()
    return when {
        DateUtils.isToday(timestampMillis) -> DateFormat.getTimeFormat(context).format(Date(timestampMillis))
        now - timestampMillis < DateUtils.WEEK_IN_MILLIS -> DateUtils.formatDateTime(
            context,
            timestampMillis,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
        )
        else -> DateFormat.getMediumDateFormat(context).format(Date(timestampMillis))
    }
}

fun initialsFor(text: String): String = text
    .split(' ', '+', '-', '_')
    .filter { it.isNotBlank() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
    .ifBlank { "?" }

/**
 * Small pill tagging a row with a line label (a SIM's name or "Voice"), so the merged inbox /
 * call log stays legible across multiple SIMs + Google Voice. Renders nothing when [text] is null.
 */
@Composable
fun LineTag(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** The user-facing label for a stored line, given its category + SIM subscription (if any). */
@Composable
fun lineLabel(line: CommunicateLine, subscriptionId: Int?, alwaysShowSim: Boolean = false): String? {
    val context = LocalContext.current
    return when (line) {
        CommunicateLine.GoogleVoice -> stringResource(R.string.line_gv)
        CommunicateLine.WhatsApp -> "WhatsApp"
        CommunicateLine.Signal -> "Signal"
        CommunicateLine.Sim -> {
            val sims = remember { SimManager.activeSims(context) }
            // Only label SIM rows when there's more than one SIM (or explicitly requested).
            if (!alwaysShowSim && sims.size <= 1) return null
            sims.firstOrNull { it.subscriptionId == subscriptionId }?.displayName
        }
    }
}

/** Convenience: tag for a stored line (SIM name / "Voice"). */
@Composable
fun LineBadge(line: CommunicateLine, subscriptionId: Int? = null, modifier: Modifier = Modifier) {
    LineTag(lineLabel(line, subscriptionId), modifier = modifier)
}

/** All selectable outgoing lines: each active SIM plus Google Voice when signed in. */
@Composable
fun rememberLineChoices(): List<LineChoice> {
    val context = LocalContext.current
    val session = remember { GoogleVoiceSession.get(context) }
    val gv by session.signedInFlow.collectAsState(initial = false)
    val waSession = remember { com.vayunmathur.communicate.data.whatsapp.WhatsAppLineSession.get(context) }
    val wa by waSession.signedInFlow.collectAsState(initial = false)
    val sigSession = remember { com.vayunmathur.communicate.data.signal.SignalLineSession.get(context) }
    val sig by sigSession.signedInFlow.collectAsState(initial = false)
    // WhatsApp/Signal are dev-only features (unofficial primary clients) — never offer in release.
    val waEnabled = wa && com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled
    val sigEnabled = sig && com.vayunmathur.communicate.data.signal.SignalFeature.enabled
    return remember(gv, waEnabled, sigEnabled) {
        SimManager.simLineChoices(context) +
            (if (gv) listOf(LineChoice.GoogleVoice) else emptyList()) +
            (if (waEnabled) listOf(LineChoice.WhatsApp) else emptyList()) +
            (if (sigEnabled) listOf(LineChoice.Signal) else emptyList())
    }
}

/** Dropdown that lets the user pick which line (SIM or Google Voice) to send/call from. */
@Composable
fun LineSelector(
    choices: List<LineChoice>,
    selected: LineChoice,
    onSelect: (LineChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(selected.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                IconArrowDropDown()
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = { onSelect(choice); expanded = false },
                )
            }
        }
    }
}
