package io.github.linuskromnow.linusexplorer.ui.browser

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FileKind

@Composable
fun FileList(state: BrowserUiState, viewModel: BrowserViewModel) {
    val location = state.location
    val listState = remember(location) {
        val (index, offset) = viewModel.scrollPosition(location)
        LazyListState(index, offset)
    }
    DisposableEffect(location, listState) {
        onDispose {
            viewModel.saveScrollPosition(location, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp), // keep the last row clear of the FAB
    ) {
        items(state.entries, key = { it.path }) { entry ->
            FileRow(
                entry = entry,
                selected = entry.path in state.selection,
                onClick = { viewModel.onEntryClick(entry) },
                onLongClick = { viewModel.onEntryLongClick(entry) },
            )
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val subtitle = remember(entry) {
        val date = if (entry.lastModified > 0) {
            DateUtils.formatDateTime(
                context,
                entry.lastModified,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
            )
        } else {
            null
        }
        val size = if (entry.isDirectory) null else Formatter.formatShortFileSize(context, entry.size)
        listOfNotNull(size, date).joinToString(" · ")
    }
    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .alpha(if (entry.isHidden) 0.6f else 1f),
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle.isEmpty()) null else ({ Text(subtitle, maxLines = 1) }),
        leadingContent = { FileIcon(entry, selected) },
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
    )
}

@Composable
private fun FileIcon(entry: FileEntry, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(if (selected) colors.primary else colors.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.Check else FileKind.of(entry).icon(),
            contentDescription = null,
            tint = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        )
    }
}

private fun FileKind.icon(): ImageVector = when (this) {
    FileKind.FOLDER -> Icons.Default.Folder
    FileKind.IMAGE -> Icons.Default.Image
    FileKind.VIDEO -> Icons.Default.Movie
    FileKind.AUDIO -> Icons.Default.Audiotrack
    FileKind.TEXT -> Icons.Default.TextSnippet
    FileKind.PDF -> Icons.Default.PictureAsPdf
    FileKind.DOCUMENT -> Icons.Default.Description
    FileKind.ARCHIVE -> Icons.Default.Archive
    FileKind.APK -> Icons.Default.Android
    FileKind.ENCRYPTED -> Icons.Default.Lock
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
fun BreadcrumbBar(crumbs: List<Breadcrumb>, onClick: (Breadcrumb) -> Unit) {
    val rowState = rememberLazyListState()
    LaunchedEffect(crumbs) {
        if (crumbs.isNotEmpty()) rowState.animateScrollToItem(crumbs.lastIndex)
    }
    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs, key = { _, crumb -> crumb.path }) { index, crumb ->
            if (index > 0) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onClick(crumb) }) {
                Text(
                    crumb.label,
                    color = if (index == crumbs.lastIndex) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Centered message; scrollable so pull-to-refresh still works on it. */
@Composable
fun MessageContent(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(96.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
