package io.github.linuskromnow.linusexplorer.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.editor.EditorPrefs
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.ui.browser.MessageContent
import kotlinx.coroutines.launch

/** Full-screen text editor for the file at [location]; calls [onClose] when the user leaves. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(location: Location, onClose: () -> Unit, viewModel: EditorViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(location) { viewModel.open(location) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> launch { snackbarHostState.showSnackbar(event.resolve(resources)) } }
    }

    val leave = {
        viewModel.close()
        onClose()
    }
    val requestLeave = { if (state.dirty) confirmLeave = true else leave() }
    BackHandler { if (state.find != null) viewModel.hideFind() else requestLeave() }

    Scaffold(
        // Drawn over the browser, which stays composed underneath; swallow touches meant for it.
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {},
        topBar = {
            var overflowOpen by remember { mutableStateOf(false) }
            TopAppBar(
                title = {
                    val title = if (state.dirty) "• ${state.name}" else state.name
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = requestLeave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    val editable = state.document != null && !state.readOnly
                    IconButton(onClick = { viewModel.save() }, enabled = editable && state.dirty && !state.isSaving) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.action_save))
                    }
                    IconButton(onClick = viewModel::undo, enabled = editable && state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.action_undo))
                    }
                    IconButton(onClick = viewModel::redo, enabled = editable && state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.action_redo))
                    }
                    IconButton(onClick = { overflowOpen = true }, enabled = state.document != null) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_find)) },
                            onClick = {
                                overflowOpen = false
                                viewModel.showFind()
                            },
                        )
                        CheckableItem(stringResource(R.string.editor_word_wrap), state.prefs.wordWrap) { viewModel.toggleWordWrap() }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editor_font_larger)) },
                            enabled = state.prefs.fontSizeSp < EditorPrefs.MAX_FONT_SIZE,
                            onClick = { viewModel.changeFontSize(+1) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editor_font_smaller)) },
                            enabled = state.prefs.fontSizeSp > EditorPrefs.MIN_FONT_SIZE,
                            onClick = { viewModel.changeFontSize(-1) },
                        )
                        CheckableItem(stringResource(R.string.editor_read_only), state.readOnly, enabled = !state.forcedReadOnly) {
                            viewModel.setReadOnly(!state.readOnlyToggle)
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                state.error != null -> MessageContent(
                    text = state.error.orEmpty(),
                    actionLabel = stringResource(R.string.action_close),
                    onAction = leave,
                )
                state.isLoading || state.document == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    state.find?.let { FindBar(it, state.readOnly, viewModel) }
                    state.notice?.let { notice ->
                        Text(
                            stringResource(notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    EditorBody(state, viewModel, Modifier.weight(1f))
                    StatusBar(state)
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.editor_unsaved_title)) },
            text = { Text(stringResource(R.string.editor_unsaved_body, state.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    viewModel.save(onSaved = leave)
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = {
                        confirmLeave = false
                        leave()
                    }) { Text(stringResource(R.string.action_discard)) }
                }
            },
        )
    }
}

@Composable
private fun CheckableItem(label: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        enabled = enabled,
        onClick = onClick,
        trailingIcon = { if (checked) Icon(Icons.Default.Check, contentDescription = null) },
    )
}

/**
 * Gutter, text field and scrolling; line numbers follow soft-wrapped lines through the text layout.
 * Without word wrap the field sits in a horizontal scroller, whose unbounded width stops it from wrapping.
 */
@Composable
private fun EditorBody(state: EditorUiState, viewModel: EditorViewModel, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val wrap = state.prefs.wordWrap
    val fontSize = state.prefs.fontSizeSp
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.4f).sp,
        color = colors.onSurface,
    )
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val gutter = remember(layout) { layout?.let(::gutterText) ?: "1" }

    val highlightColor = colors.tertiaryContainer
    val highlight = state.find?.current
    val transformation = remember(highlight, highlightColor) {
        if (highlight == null) {
            VisualTransformation.None
        } else {
            VisualTransformation { text ->
                val styled = buildAnnotatedString {
                    append(text)
                    val start = highlight.start.coerceIn(0, text.length)
                    val end = highlight.end.coerceIn(start, text.length)
                    addStyle(SpanStyle(background = highlightColor), start, end)
                }
                TransformedText(styled, OffsetMapping.Identity)
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportHeight = with(LocalDensity.current) { maxHeight.toPx() }
        val viewportWidth = maxWidth
        val viewportDp = maxHeight

        // Scroll the cursor (the current find match) into view.
        LaunchedEffect(state.revealTick) {
            val current = layout
            if (state.revealTick == 0 || current == null) return@LaunchedEffect
            val offset = state.value.selection.start.coerceIn(0, current.layoutInput.text.length)
            val rect = current.getCursorRect(offset)
            verticalScroll.animateScrollTo((rect.top - viewportHeight / 3).toInt().coerceAtLeast(0))
            if (!wrap) horizontalScroll.animateScrollTo((rect.left - 100).toInt().coerceAtLeast(0))
        }

        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(verticalScroll),
        ) {
            Text(
                gutter,
                style = textStyle.copy(color = colors.onSurfaceVariant),
                textAlign = TextAlign.End,
                modifier = Modifier
                    .heightIn(min = viewportDp)
                    .background(colors.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp),
            )
            Box(if (wrap) Modifier.weight(1f) else Modifier.weight(1f).horizontalScroll(horizontalScroll)) {
                BasicTextField(
                    value = state.value,
                    onValueChange = viewModel::onValueChange,
                    readOnly = state.readOnly,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(colors.primary),
                    visualTransformation = transformation,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .heightIn(min = viewportDp)
                        .let { if (wrap) it.fillMaxWidth() else it.widthIn(min = viewportWidth - 56.dp) }
                        .padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** One entry per visual line: the number on the first line of a paragraph, blank on wrapped continuations. */
private fun gutterText(layout: TextLayoutResult): String {
    val text = layout.layoutInput.text.text
    val out = StringBuilder()
    var number = 0
    for (line in 0 until layout.lineCount) {
        if (line > 0) out.append('\n')
        val start = layout.getLineStart(line)
        if (start == 0 || text[start - 1] == '\n') out.append(++number)
    }
    return out.toString()
}

@Composable
private fun StatusBar(state: EditorUiState) {
    val document = state.document ?: return
    val encoding = document.encoding.label + if (document.hasBom) " BOM" else ""
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Text(
            "$encoding · ${document.lineEnding.label}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FindBar(find: FindState, readOnly: Boolean, viewModel: EditorViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = find.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text(stringResource(R.string.editor_find_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.findNext() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                )
                IconButton(onClick = viewModel::findPrevious) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.editor_find_previous))
                }
                IconButton(onClick = viewModel::findNext) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.editor_find_next))
                }
                IconButton(onClick = viewModel::hideFind) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.editor_close_find))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = find.caseSensitive,
                    onClick = { viewModel.setCaseSensitive(!find.caseSensitive) },
                    label = { Text(stringResource(R.string.editor_match_case)) },
                )
                if (find.query.isNotEmpty()) {
                    Text(
                        if (find.matchCount == 0) {
                            stringResource(R.string.editor_no_matches)
                        } else {
                            pluralStringResource(R.plurals.editor_match_count, find.matchCount, find.matchCount)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            if (!readOnly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = find.replacement,
                        onValueChange = viewModel::setReplacement,
                        placeholder = { Text(stringResource(R.string.editor_replace_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::replaceCurrent) { Text(stringResource(R.string.editor_replace)) }
                    TextButton(onClick = viewModel::replaceAll) { Text(stringResource(R.string.editor_replace_all)) }
                }
            }
        }
    }
}
