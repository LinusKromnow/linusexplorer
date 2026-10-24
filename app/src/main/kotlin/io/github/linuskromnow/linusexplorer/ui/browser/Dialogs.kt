package io.github.linuskromnow.linusexplorer.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.fs.ConflictResolution
import io.github.linuskromnow.linusexplorer.tasks.ConflictRequest
import io.github.linuskromnow.linusexplorer.tasks.TransferState
import androidx.compose.foundation.layout.Row
import io.github.linuskromnow.linusexplorer.fs.vault.VaultFileSystem
import io.github.linuskromnow.linusexplorer.security.VaultBiometrics
import io.github.linuskromnow.linusexplorer.security.findFragmentActivity
import io.github.linuskromnow.linusexplorer.fs.VfsPaths

@Composable
fun BrowserDialogHost(dialog: BrowserDialog, viewModel: BrowserViewModel) {
    when (dialog) {
        BrowserDialog.NewFolder -> NameDialog(
            title = stringResource(R.string.action_new_folder),
            confirmLabel = stringResource(R.string.action_create),
            onConfirm = viewModel::createFolder,
            onDismiss = viewModel::dismissDialog,
        )
        BrowserDialog.NewFile -> NameDialog(
            title = stringResource(R.string.action_new_file),
            confirmLabel = stringResource(R.string.action_create),
            onConfirm = viewModel::createFile,
            onDismiss = viewModel::dismissDialog,
        )
        is BrowserDialog.Rename -> NameDialog(
            title = stringResource(R.string.action_rename),
            confirmLabel = stringResource(R.string.action_rename),
            initialName = dialog.entry.name,
            // Preselect "photo" in "photo.jpg" so typing keeps the extension.
            selectBaseNameOnly = !dialog.entry.isDirectory,
            onConfirm = { viewModel.rename(dialog.entry, it) },
            onDismiss = viewModel::dismissDialog,
        )
        is BrowserDialog.ConfirmDelete -> {
            val entries = dialog.entries
            AlertDialog(
                onDismissRequest = viewModel::dismissDialog,
                title = {
                    Text(
                        entries.singleOrNull()?.let { stringResource(R.string.delete_one_title, it.name) }
                            ?: pluralStringResource(R.plurals.delete_many_title, entries.size, entries.size),
                    )
                },
                text = { Text(stringResource(R.string.delete_warning)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.delete(entries) }) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
        is BrowserDialog.Compress -> CompressDialog(
            dialog = dialog,
            onConfirm = { name, format, password -> viewModel.compress(dialog.entries, name, format, password) },
            onDismiss = viewModel::dismissDialog,
        )
        is BrowserDialog.Encrypt -> NewPasswordDialog(
            title = stringResource(R.string.action_encrypt),
            message = stringResource(R.string.encrypt_body),
            confirmLabel = stringResource(R.string.action_encrypt),
            option = stringResource(R.string.encrypt_delete_originals),
            optionDefault = true,
            onConfirm = { password, deleteOriginals -> viewModel.encrypt(dialog.entries, password, deleteOriginals) },
            onDismiss = viewModel::dismissDialog,
        )
        is BrowserDialog.Decrypt -> DecryptDialog(
            dialog = dialog,
            onConfirm = { password, deleteEncrypted -> viewModel.decrypt(dialog, password, deleteEncrypted) },
            onDismiss = viewModel::dismissDialog,
        )
        BrowserDialog.CreateVault -> NewPasswordDialog(
            title = stringResource(R.string.vault_create_title),
            message = stringResource(R.string.vault_create_body),
            confirmLabel = stringResource(R.string.action_create),
            onConfirm = { password, _ -> viewModel.createVault(password) },
            onDismiss = viewModel::dismissDialog,
        )
        BrowserDialog.ChangeVaultPassword -> NewPasswordDialog(
            title = stringResource(R.string.action_change_vault_password),
            message = stringResource(R.string.vault_change_password_body),
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { password, _ -> viewModel.changeVaultPassword(password) },
            onDismiss = viewModel::dismissDialog,
        )
        is BrowserDialog.Password -> {
            val activity = LocalContext.current.findFragmentActivity()
            val biometrics = viewModel.vaultBiometrics
            val prompt = VaultBiometrics.Prompt(stringResource(R.string.vault_unlock_title), stringResource(R.string.lock_biometric_negative))
            val onBiometric = if (dialog.fsId == VaultFileSystem.ID && activity != null && biometrics.isEnabled && biometrics.isAvailable(activity)) {
                {
                    biometrics.unlock(
                        activity,
                        prompt,
                        onKey = viewModel::unlockVaultWithKey,
                        onInvalidated = viewModel::onVaultBiometricChanged,
                    )
                }
            } else {
                null
            }
            PasswordDialog(
                dialog = dialog,
                onSubmit = { viewModel.submitPassword(dialog, it) },
                onDismiss = viewModel::dismissDialog,
                onBiometric = onBiometric,
            )
        }
        is BrowserDialog.RestoreVault -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.vault_restore_title)) },
            text = { Text(stringResource(R.string.vault_restore_body, dialog.entry.name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.restoreVault(dialog.entry) }) {
                    Text(stringResource(R.string.action_restore), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    selectBaseNameOnly: Boolean = false,
) {
    var value by remember {
        val dot = initialName.lastIndexOf('.')
        val end = if (selectBaseNameOnly && dot > 0) dot else initialName.length
        mutableStateOf(TextFieldValue(initialName, selection = TextRange(0, end)))
    }
    val name = value.text.trim()
    val valid = VfsPaths.isValidName(name) && name != initialName
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = name.isNotEmpty() && !VfsPaths.isValidName(name),
                label = { Text(stringResource(R.string.field_name)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun TransferDialog(transfer: TransferState, onCancel: () -> Unit, onHide: () -> Unit) {
    val progress = transfer.progress
    AlertDialog(
        onDismissRequest = onHide,
        title = { Text(stringResource(transfer.kind.titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progress == null) {
                    Text(stringResource(R.string.transfer_preparing))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text(progress.currentName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        pluralStringResource(
                            R.plurals.transfer_files_progress,
                            progress.filesTotal,
                            progress.filesDone,
                            progress.filesTotal,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onHide) { Text(stringResource(R.string.action_hide)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun ConflictDialog(conflict: ConflictRequest, onResolve: (ConflictResolution, applyToAll: Boolean) -> Unit, onCancel: () -> Unit) {
    var applyToAll by remember(conflict) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {}, // the task waits for an answer
        title = { Text(stringResource(R.string.conflict_title, conflict.existing.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (conflict.bothFolders) R.string.conflict_body_folder else R.string.conflict_body_file))
                CheckboxRow(stringResource(R.string.conflict_apply_to_all), applyToAll) { applyToAll = it }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onResolve(ConflictResolution.SKIP, applyToAll) }) {
                    Text(stringResource(R.string.conflict_skip))
                }
                TextButton(onClick = { onResolve(ConflictResolution.KEEP_BOTH, applyToAll) }) {
                    Text(stringResource(R.string.conflict_keep_both))
                }
                TextButton(onClick = { onResolve(ConflictResolution.REPLACE, applyToAll) }) {
                    Text(stringResource(if (conflict.bothFolders) R.string.conflict_merge else R.string.conflict_replace))
                }
            }
        },
    )
}

@Composable
fun PermissionContent(onGrantClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.permission_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onGrantClick) { Text(stringResource(R.string.permission_grant)) }
    }
}
