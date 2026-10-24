package io.github.linuskromnow.linusexplorer.ui.connections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.network.Connection
import io.github.linuskromnow.linusexplorer.fs.network.Protocol
import io.github.linuskromnow.linusexplorer.fs.network.s3.S3FileSystem
import io.github.linuskromnow.linusexplorer.ui.browser.CheckboxRow
import io.github.linuskromnow.linusexplorer.ui.browser.PasswordField

private const val MAX_KEY_FILE_BYTES = 64 * 1024

/** Adds a new connection ([existing] = null) or edits/deletes a saved one. */
@Composable
fun ConnectionDialog(
    existing: Connection?,
    onSave: (Connection) -> Unit,
    onDelete: (Connection) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var protocol by rememberSaveable { mutableStateOf(existing?.protocol ?: Protocol.SFTP) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(existing?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: protocol.defaultPort).toString()) }
    var username by rememberSaveable { mutableStateOf(existing?.username.orEmpty()) }
    var useKey by rememberSaveable { mutableStateOf(existing?.privateKey != null) }
    var password by rememberSaveable { mutableStateOf(existing?.password.orEmpty()) }
    var rememberPassword by rememberSaveable { mutableStateOf(existing == null || existing.password != null) }
    var privateKey by rememberSaveable { mutableStateOf(existing?.privateKey) }
    var keyPassphrase by rememberSaveable { mutableStateOf(existing?.keyPassphrase.orEmpty()) }
    var path by rememberSaveable { mutableStateOf(existing?.path ?: VfsPaths.ROOT) }
    var share by rememberSaveable { mutableStateOf(existing?.share.orEmpty()) }
    var region by rememberSaveable { mutableStateOf(existing?.region.orEmpty()) }
    var keyError by rememberSaveable { mutableStateOf(false) }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_KEY_FILE_BYTES + 1) } }.getOrNull()
        keyError = bytes == null || bytes.size > MAX_KEY_FILE_BYTES || !String(bytes).contains("PRIVATE KEY")
        if (!keyError) privateKey = String(bytes!!)
    }

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val keyAuth = useKey && protocol.supportsKeys
    val valid = host.isNotBlank() && portNumber != null &&
        (username.isNotBlank() || protocol.allowsAnonymous) && (!keyAuth || privateKey != null) &&
        (!protocol.needsShare || share.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.connection_add else R.string.connection_edit)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Protocol.entries.forEach { option ->
                        FilterChip(
                            selected = protocol == option,
                            onClick = {
                                if (port == protocol.defaultPort.toString()) port = option.defaultPort.toString()
                                protocol = option
                            },
                            label = { Text(option.label) },
                        )
                    }
                }
                Field(name, { name = it }, stringResource(R.string.connection_name_optional))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(host, { host = it.trim() }, stringResource(R.string.connection_host), Modifier.weight(2f), KeyboardType.Uri)
                    Field(port, { port = it.filter(Char::isDigit) }, stringResource(R.string.connection_port), Modifier.weight(1f), KeyboardType.Number)
                }
                Field(
                    username,
                    { username = it.trim() },
                    stringResource(
                        when {
                            protocol.isS3 -> R.string.connection_access_key
                            protocol.allowsAnonymous -> R.string.connection_username_anonymous
                            else -> R.string.connection_username
                        },
                    ),
                )
                if (protocol.supportsKeys) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !useKey, onClick = { useKey = false }, label = { Text(stringResource(R.string.connection_auth_password)) })
                        FilterChip(selected = useKey, onClick = { useKey = true }, label = { Text(stringResource(R.string.connection_auth_key)) })
                    }
                }
                if (!keyAuth) {
                    PasswordField(password, { password = it }, stringResource(if (protocol.isS3) R.string.connection_secret_key else R.string.field_password))
                    CheckboxRow(stringResource(R.string.connection_remember_password), rememberPassword) { rememberPassword = it }
                } else {
                    OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (privateKey == null) R.string.connection_choose_key else R.string.connection_key_loaded))
                    }
                    if (keyError) {
                        Text(stringResource(R.string.connection_key_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    PasswordField(keyPassphrase, { keyPassphrase = it }, stringResource(R.string.connection_key_passphrase))
                }
                if (protocol.needsShare) {
                    Field(share, { share = it.trim().trim('/', '\\') }, stringResource(if (protocol.isS3) R.string.connection_bucket else R.string.connection_share))
                }
                if (protocol.isS3) {
                    Field(region, { region = it.trim() }, stringResource(R.string.connection_region, S3FileSystem.guessRegion(host)))
                }
                if (protocol.isWebDav) Field(share, { share = it.trim() }, stringResource(R.string.connection_server_path))
                Field(path, { path = it.trim() }, stringResource(R.string.connection_path))
                if (existing != null) {
                    TextButton(onClick = { onDelete(existing) }) {
                        Text(stringResource(R.string.connection_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        Connection(
                            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            protocol = protocol,
                            host = host,
                            port = portNumber!!,
                            username = username,
                            password = if (!keyAuth && rememberPassword) password else null,
                            privateKey = if (keyAuth) privateKey else null,
                            keyPassphrase = if (keyAuth) keyPassphrase.ifEmpty { null } else null,
                            path = VfsPaths.normalize(path.ifBlank { VfsPaths.ROOT }),
                            share = if (protocol.needsShare || protocol.isWebDav) share else "",
                            region = if (protocol.isS3) region else "",
                        ),
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}

/** InputStream.readNBytes needs API 33; key files are tiny, so a simple bounded read does. */
private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** First connection to a server, or a server whose key changed (possible attack). */
@Composable
fun TrustHostDialog(
    host: String,
    port: Int,
    fingerprint: String,
    changed: Boolean,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (changed) R.string.trust_host_changed_title else R.string.trust_host_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (changed) R.string.trust_host_changed_body else R.string.trust_host_body, "$host:$port"),
                    color = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(
                    stringResource(if (changed) R.string.trust_host_accept_new else R.string.trust_host_accept),
                    color = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
