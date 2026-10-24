package io.github.linuskromnow.linusexplorer.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.linuskromnow.linusexplorer.R

/** Minimum length for new passwords; short ones are trivial to brute-force offline. */
private const val MIN_PASSWORD_LENGTH = 6

/**
 * Asks for a new password twice. [option] adds a checkbox (e.g. "Delete originals") whose value
 * is passed to [onConfirm].
 */
@Composable
fun NewPasswordDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: (password: String, optionChecked: Boolean) -> Unit,
    onDismiss: () -> Unit,
    option: String? = null,
    optionDefault: Boolean = false,
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var optionChecked by remember { mutableStateOf(optionDefault) }
    val tooShort = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val mismatch = repeat.isNotEmpty() && repeat != password
    val valid = password.length >= MIN_PASSWORD_LENGTH && repeat == password
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.field_password),
                    isError = tooShort,
                    supportingText = if (tooShort) {
                        pluralStringResource(R.plurals.password_too_short, MIN_PASSWORD_LENGTH, MIN_PASSWORD_LENGTH)
                    } else {
                        null
                    },
                    modifier = Modifier.focusRequester(focusRequester),
                )
                PasswordField(
                    value = repeat,
                    onValueChange = { repeat = it },
                    label = stringResource(R.string.field_password_repeat),
                    isError = mismatch,
                    supportingText = if (mismatch) stringResource(R.string.password_mismatch) else null,
                    onDone = { if (valid) onConfirm(password, optionChecked) },
                )
                if (option != null) CheckboxRow(option, optionChecked) { optionChecked = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password, optionChecked) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun DecryptDialog(
    dialog: BrowserDialog.Decrypt,
    onConfirm: (password: String, deleteEncrypted: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var deleteEncrypted by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.entry.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.decrypt_body))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.field_password),
                    isError = dialog.wrongPassword,
                    supportingText = if (dialog.wrongPassword) stringResource(R.string.password_wrong) else null,
                    onDone = { if (password.isNotEmpty()) onConfirm(password, deleteEncrypted) },
                    modifier = Modifier.focusRequester(focusRequester),
                )
                if (!dialog.open) {
                    CheckboxRow(stringResource(R.string.decrypt_delete_encrypted), deleteEncrypted) { deleteEncrypted = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password, deleteEncrypted) }, enabled = password.isNotEmpty()) {
                Text(stringResource(if (dialog.open) R.string.action_open else R.string.action_decrypt))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun CheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
