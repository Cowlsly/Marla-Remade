package com.vayunmathur.communicate.ui.signal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.data.signal.SignalLineSession
import com.vayunmathur.communicate.data.signal.registration.RegistrationHttpClient
import com.vayunmathur.library.ui.DetailScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone-number registration UI for the Signal **primary client**.
 *
 * Mirrors [com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationScreen]:
 *  enter number → request OTP (SMS/voice) → enter OTP → verify → success.
 *  On success marks the line signed-in via [SignalLineSession] and calls [onRegistered].
 *
 *  ⚠️ Registering as primary links Signal to this app — use a test number.
 */
private enum class RegStep { EnterNumber, EnterCode, Done }

@Composable
fun SignalRegistrationScreen(
    onBack: () -> Unit,
    onRegistered: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { RegistrationHttpClient(context.applicationContext) }
    val session = remember { SignalLineSession.get(context) }

    var step by remember { mutableStateOf(RegStep.EnterNumber) }
    var cc by remember { mutableStateOf("1") }
    var number by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun e164(): String = "+${cc.filter { it.isDigit() }}${number.filter { it.isDigit() }}"

    fun run(block: suspend () -> Unit) {
        busy = true
        status = null
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                status = "Error: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    DetailScaffold(title = "Register Signal") {
        Text(
            "Registers this number as the PRIMARY Signal device. This will link Signal to this app — " +
                "use a test number.",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )

        when (step) {
            RegStep.EnterNumber -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cc,
                        onValueChange = { cc = it.filter(Char::isDigit) },
                        label = { Text("CC") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.width(90.dp),
                    )
                    OutlinedTextField(
                        value = number,
                        onValueChange = { number = it.filter(Char::isDigit) },
                        label = { Text("Phone number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && number.isNotBlank(),
                        onClick = {
                            run {
                                val r = withContext(Dispatchers.IO) { client.requestSmsCode(e164()) }
                                status = "code: ${r.status}${r.reason?.let { " ($it)" } ?: ""}"
                                if (r.ok) step = RegStep.EnterCode
                            }
                        },
                    ) { Text("Request SMS code") }
                    OutlinedButton(
                        enabled = !busy && number.isNotBlank(),
                        onClick = {
                            run {
                                val r = withContext(Dispatchers.IO) { client.requestVoiceCode(e164()) }
                                status = "code: ${r.status}${r.reason?.let { " ($it)" } ?: ""}"
                                if (r.ok) step = RegStep.EnterCode
                            }
                        },
                    ) { Text("Voice") }
                }
                OutlinedButton(
                    enabled = !busy && number.isNotBlank(),
                    onClick = {
                        run {
                            val r = withContext(Dispatchers.IO) { client.checkExists(e164()) }
                            status = "exists: ${r.exists}${r.reason?.let { " ($it)" } ?: ""}"
                        }
                    },
                ) { Text("Check number (no SMS)") }
            }

            RegStep.EnterCode -> {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit) },
                    label = { Text("6-digit code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !busy && code.length >= 4,
                    onClick = {
                        run {
                            val r = withContext(Dispatchers.IO) { client.verifyCode(e164(), code) }
                            status = "register: ${r.status}${r.reason?.let { " ($it)" } ?: ""}"
                            if (r.ok && r.auth != null) {
                                session.markRegistered(context, r.auth)
                                step = RegStep.Done
                            }
                        }
                    },
                ) { Text("Verify") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { step = RegStep.EnterNumber; status = null },
                ) { Text("Change number") }
            }

            RegStep.Done -> {
                Text("Registered ✓  Signal primary line is active.")
                Button(onClick = onRegistered) { Text("Done") }
            }
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Working…")
            }
        }
        status?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}
