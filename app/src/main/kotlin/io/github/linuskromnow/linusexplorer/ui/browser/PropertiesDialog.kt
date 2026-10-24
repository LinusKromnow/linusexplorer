package io.github.linuskromnow.linusexplorer.ui.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.properties.ChecksumAlgorithm
import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem

/** Details of the selected items: metadata at once, folder sizes and checksums as they are computed. */
@Composable
fun PropertiesDialog(viewModel: BrowserViewModel) {
    val state by viewModel.properties.collectAsStateWithLifecycle()
    val properties = state ?: return
    val context = LocalContext.current
    val single = properties.single
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = {
            Text(
                single?.name ?: pluralStringResource(R.plurals.properties_items, properties.entries.size, properties.entries.size),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(stringResource(R.string.properties_location), locationOf(properties))
                if (single != null) {
                    Field(stringResource(R.string.properties_type), typeOf(context, single))
                }
                Field(stringResource(R.string.properties_size), sizeText(context, properties))
                val stats = properties.stats
                if (stats != null) {
                    Field(stringResource(R.string.properties_contents), contentsText(stats.files, stats.folders))
                    if (stats.unreadable > 0) {
                        Field(stringResource(R.string.properties_unreadable), stats.unreadable.toString())
                    }
                }
                if (properties.measuring) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = viewModel::cancelMeasure) { Text(stringResource(R.string.action_stop)) }
                }
                if (single != null && single.lastModified > 0) {
                    Field(stringResource(R.string.properties_modified), DateUtils.formatDateTime(
                        context,
                        single.lastModified,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
                    ))
                }
                properties.access?.let { access ->
                    val parts = listOfNotNull(
                        stringResource(R.string.properties_readable).takeIf { access.readable },
                        stringResource(R.string.properties_writable).takeIf { access.writable },
                        stringResource(R.string.properties_executable).takeIf { access.executable },
                    )
                    Field(stringResource(R.string.properties_permissions), parts.joinToString(", ").ifEmpty { stringResource(R.string.properties_no_access) })
                }
                if (single != null && !single.isDirectory) {
                    HorizontalDivider()
                    ChecksumSection(properties, viewModel, context)
                }
                properties.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun ChecksumSection(properties: PropertiesState, viewModel: BrowserViewModel, context: Context) {
    Text(stringResource(R.string.properties_checksums), style = MaterialTheme.typography.titleSmall)
    when {
        properties.hashing -> {
            LinearProgressIndicator(progress = { properties.hashProgress }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = viewModel::cancelChecksums) { Text(stringResource(R.string.action_stop)) }
        }
        properties.checksums.isEmpty() -> {
            TextButton(onClick = viewModel::computeChecksums) { Text(stringResource(R.string.properties_calculate)) }
        }
        else -> ChecksumAlgorithm.entries.forEach { algorithm ->
            val value = properties.checksums[algorithm] ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(algorithm.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer {
                        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(algorithm.label, value))
                    viewModel.onChecksumCopied()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.properties_copy, algorithm.label))
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(Modifier.padding(0.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Parent folder of a single item, or the shared parent of several (blank if they differ, e.g. search results). */
@Composable
private fun locationOf(properties: PropertiesState): String {
    val parents = properties.entries.map { VfsPaths.parent(it.path) ?: it.path }.distinct()
    val parent = parents.singleOrNull() ?: return stringResource(R.string.properties_various_folders)
    return if (properties.fsId == LocalFileSystem.ID) parent else "${properties.fsId}: $parent"
}

private fun typeOf(context: Context, entry: FileEntry): String = when {
    entry.isDirectory && entry.isSymlink -> context.getString(R.string.properties_type_folder_link)
    entry.isDirectory -> context.getString(R.string.properties_type_folder)
    else -> {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.extension) ?: "application/octet-stream"
        if (entry.isSymlink) context.getString(R.string.properties_type_link, mime) else mime
    }
}

private fun sizeText(context: Context, properties: PropertiesState): String {
    val bytes = properties.totalBytes
    val text = context.getString(R.string.properties_size_value, Formatter.formatFileSize(context, bytes), bytes)
    return if (properties.measuring) "$text…" else text
}

@Composable
private fun contentsText(files: Int, folders: Int): String =
    pluralStringResource(R.plurals.properties_files, files, files) + ", " +
        pluralStringResource(R.plurals.properties_folders, folders, folders)
