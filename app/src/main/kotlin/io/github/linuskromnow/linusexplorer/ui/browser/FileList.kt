package io.github.linuskromnow.linusexplorer.ui.browser

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FileKind
import io.github.linuskromnow.linusexplorer.fs.ViewMode
import io.github.linuskromnow.linusexplorer.thumbnails.ThumbnailSource
import io.github.linuskromnow.linusexplorer.thumbnails.Thumbnails

@Composable
fun FileList(state: BrowserUiState, viewModel: BrowserViewModel) {
    val location = state.location
    val grid = state.prefs.viewMode == ViewMode.GRID
    fun thumbnailFor(entry: FileEntry) =
        if (state.prefs.showThumbnails) Thumbnails.sourceFor(location.fsId, entry) else null

    if (grid) {
        val gridState = remember(location) {
            val (index, offset) = viewModel.scrollPosition(location)
            LazyGridState(index, offset)
        }
        DisposableEffect(location, gridState) {
            onDispose {
                viewModel.saveScrollPosition(location, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(GRID_CELL_MIN_WIDTH), // as many columns as fit the width
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp), // clear of the FAB
        ) {
            gridItems(state.entries, key = { it.path }) { entry ->
                FileCell(
                    entry = entry,
                    selected = entry.path in state.selection,
                    thumbnail = thumbnailFor(entry),
                    imageLoader = viewModel.imageLoader,
                    onClick = { viewModel.onEntryClick(entry) },
                    onLongClick = { viewModel.onEntryLongClick(entry) },
                )
            }
        }
        return
    }
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
                thumbnail = thumbnailFor(entry),
                imageLoader = viewModel.imageLoader,
                onClick = { viewModel.onEntryClick(entry) },
                onLongClick = { viewModel.onEntryLongClick(entry) },
            )
        }
    }
}

/** One tile of the grid view: thumbnail or file-type icon above a two-line name. */
@Composable
private fun FileCell(
    entry: FileEntry,
    selected: Boolean,
    thumbnail: ThumbnailSource?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .alpha(if (entry.isHidden) 0.6f else 1f)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FileIcon(
            entry, selected, thumbnail, imageLoader,
            size = GRID_ICON_SIZE,
            shape = RoundedCornerShape(12.dp),
            iconSize = 40.dp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Search results as a list; each row shows where the match lives, relative to the folder searched. */
@Composable
fun SearchResultList(state: BrowserUiState, search: SearchState, viewModel: BrowserViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(search.results, key = { it.entry.path }) { hit ->
            FileRow(
                entry = hit.entry,
                selected = hit.entry.path in state.selection,
                thumbnail = if (state.prefs.showThumbnails) Thumbnails.sourceFor(state.location.fsId, hit.entry) else null,
                imageLoader = viewModel.imageLoader,
                onClick = { viewModel.onEntryClick(hit.entry) },
                onLongClick = { viewModel.onEntryLongClick(hit.entry) },
                subtitleOverride = hit.relativePath,
            )
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    thumbnail: ThumbnailSource?,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    subtitleOverride: String? = null,
) {
    val context = LocalContext.current
    val subtitle = subtitleOverride ?: remember(entry) {
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
        leadingContent = { FileIcon(entry, selected, thumbnail, imageLoader) },
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
    )
}

@Composable
private fun FileIcon(
    entry: FileEntry,
    selected: Boolean,
    thumbnail: ThumbnailSource?,
    imageLoader: ImageLoader,
    size: Dp = ICON_SIZE,
    shape: Shape = CircleShape,
    iconSize: Dp = 24.dp,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(if (selected) colors.primary else colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // The file-type icon doubles as placeholder and as the fallback when decoding fails.
        var thumbnailLoaded by remember(thumbnail) { mutableStateOf(false) }
        val iconAlpha by animateFloatAsState(if (thumbnailLoaded && !selected) 0f else 1f, label = "iconAlpha")
        Icon(
            imageVector = if (selected) Icons.Default.Check else FileKind.of(entry).icon(),
            contentDescription = null,
            modifier = Modifier.size(iconSize).alpha(iconAlpha),
            tint = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        )
        if (thumbnail != null) {
            val context = LocalContext.current
            val sizePx = with(LocalDensity.current) { size.roundToPx() }
            val request = remember(thumbnail, sizePx) {
                ImageRequest.Builder(context)
                    .data(thumbnail)
                    .size(sizePx)
                    .precision(Precision.INEXACT)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(if (thumbnail.allowDiskCache) CachePolicy.ENABLED else CachePolicy.DISABLED)
                    .build()
            }
            // Kept in the tree while selected so the bitmap does not reload when deselecting.
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().alpha(if (selected) 0f else 1f),
                onState = { thumbnailLoaded = it is AsyncImagePainter.State.Success },
            )
        }
    }
}

private val ICON_SIZE = 40.dp
private val GRID_ICON_SIZE = 88.dp
private val GRID_CELL_MIN_WIDTH = 104.dp

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
