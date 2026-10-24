package io.github.linuskromnow.linusexplorer.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.ui.browser.CheckboxRow
import io.github.linuskromnow.linusexplorer.ui.browser.PasswordField

private const val MIN_PIN_LENGTH = 4

/** Turns the app lock on (choosing a PIN), changes it, toggles fingerprint unlock or turns it off. */
@Composable
fun AppLockSettingsDialog(
    state: AppLockUiState,
    biometricAvailable: Boolean,
    onEnable: (pin: String, biometric: Boolean) -> Unit,
    onSetBiometric: (Boolean) -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    var choosingPin by remember { mutableStateOf(!state.enabled) }
    var pin by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var biometric by remember { mutableStateOf(state.biometric || (!state.enabled && biometricAvailable)) }

    if (!choosingPin) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.app_lock_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.app_lock_on))
                    if (biometricAvailable) {
                        CheckboxRow(stringResource(R.string.app_lock_biometric), state.biometric, onSetBiometric)
                    }
                    TextButton(onClick = { choosingPin = true }) { Text(stringResource(R.string.app_lock_change_pin)) }
                }
            },
            confirmButton = {
                TextButton(onClick = onDisable) {
                    Text(stringResource(R.string.app_lock_turn_off), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        )
        return
    }

    val digitsOnly = pin.all { it.isDigit() } && repeat.all { it.isDigit() }
    val valid = digitsOnly && pin.length >= MIN_PIN_LENGTH && repeat == pin
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (state.enabled) R.string.app_lock_change_pin else R.string.app_lock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.app_lock_body))
                PasswordField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit) },
                    label = stringResource(R.string.field_pin),
                    keyboardType = KeyboardType.NumberPassword,
                    isError = pin.isNotEmpty() && pin.length < MIN_PIN_LENGTH,
                )
                PasswordField(
                    value = repeat,
                    onValueChange = { repeat = it.filter(Char::isDigit) },
                    label = stringResource(R.string.field_pin_repeat),
                    keyboardType = KeyboardType.NumberPassword,
                    isError = repeat.isNotEmpty() && repeat != pin,
                    onDone = { if (valid) onEnable(pin, biometric && biometricAvailable) },
                )
                if (biometricAvailable) {
                    CheckboxRow(stringResource(R.string.app_lock_biometric), biometric) { biometric = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEnable(pin, biometric && biometricAvailable) }, enabled = valid) {
                Text(stringResource(if (state.enabled) R.string.action_save else R.string.app_lock_turn_on))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
