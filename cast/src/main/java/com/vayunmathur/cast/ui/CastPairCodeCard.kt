package com.vayunmathur.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.cast.R
import com.vayunmathur.cast.platform.CastActions
import com.vayunmathur.cast.platform.CastUiState
import com.vayunmathur.cast.protocol.PairCode
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text

/**
 * The six digits the TV is showing.
 *
 * The whole of the trust decision the user makes, so it says plainly which TV is asking and how many
 * tries are left. Digits only, and the button stays disabled until there are six of them: a typo that
 * spent one of three attempts would be a bad trade for no benefit, so it is refused locally.
 */
@Composable
fun CastPairCodeCard(
    state: CastUiState,
    actions: CastActions,
    modifier: Modifier = Modifier,
) {
    var code by remember { mutableStateOf("") }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(
                    R.string.cast_pair_title,
                    state.connectedDevice?.friendlyName ?: "",
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.cast_pair_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.pairCodeChanged) {
                Text(
                    stringResource(R.string.cast_pair_new_code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (state.pairAttemptsLeft in 1 until PairCode.MAX_ATTEMPTS) {
                Text(
                    pluralStringResource(
                        R.plurals.cast_pair_attempts,
                        state.pairAttemptsLeft,
                        state.pairAttemptsLeft,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = code,
                // Filtered rather than validated on submit: a field that silently refuses a letter is
                // clearer than one that accepts it and then rejects the whole code.
                onValueChange = { entered ->
                    code = entered.filter { it in '0'..'9' }.take(PairCode.DIGITS)
                },
                label = { Text(stringResource(R.string.cast_pair_code_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    actions.submitPairCode(code)
                    code = ""
                },
                enabled = PairCode.isWellFormed(code),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cast_pair_submit))
            }
        }
    }
}
