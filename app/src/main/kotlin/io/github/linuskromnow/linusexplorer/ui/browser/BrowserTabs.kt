package io.github.linuskromnow.linusexplorer.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.security.SecureWindow

/** What a browser pane may do with tabs; see [BrowserHost]. */
class TabActions(
    val onNewTab: (Location) -> Unit,
    /** Null when there is only one tab. */
    val onCloseTab: (() -> Unit)?,
    /** Dual-pane state; null where the window is too narrow for two panes. */
    val dualPane: Boolean?,
    val onToggleDualPane: () -> Unit,
)

/** Windows at least this wide (landscape tablets, unfolded foldables) can show two panes. */
private val DUAL_PANE_MIN_WIDTH = 720.dp

/**
 * Hosts the browser tabs and, on wide windows, a second pane. Every tab and pane has its own
 * [BrowserViewModel] (own folder, history and selection); the clipboard is shared, so files copied
 * in one can be pasted in another.
 */
@Composable
fun BrowserHost(
    onEditFile: (Location) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecycleBin: () -> Unit,
    appLocked: Boolean,
) {
    var tabIds by rememberSaveable { mutableStateOf(listOf(0)) }
    var activeTab by rememberSaveable { mutableIntStateOf(0) }
    var nextTabId by rememberSaveable { mutableIntStateOf(1) }
    var rightFocused by rememberSaveable { mutableStateOf(false) }
    // Where a just-opened tab starts; only needed until its view model picked it up.
    var pendingStarts by rememberSaveable(stateSaver = PendingStartsSaver) { mutableStateOf(emptyMap<Int, Location>()) }

    val tabModels = tabIds.associateWith { id -> viewModel<BrowserViewModel>(key = tabKey(id)) }
    val active = tabModels.getValue(activeTab)
    val leftState by active.state.collectAsStateWithLifecycle()

    // App-wide, so switching tabs never looks like leaving the app (which would start the vault's auto-lock clock).
    val currentActive by rememberUpdatedState(active)
    var backgroundSince by remember { mutableStateOf<Long?>(null) }
    LifecycleStartEffect(Unit) {
        backgroundSince?.let { currentActive.onAppForeground(System.currentTimeMillis() - it) }
        backgroundSince = null
        onStopOrDispose { backgroundSince = System.currentTimeMillis() }
    }
    LaunchedEffect(Unit) { active.checkRemoteEdits() }

    fun openTab(location: Location) {
        val id = nextTabId++
        pendingStarts = pendingStarts + (id to location)
        tabIds = tabIds.toMutableList().apply { add(indexOf(activeTab) + 1, id) }
        activeTab = id
        rightFocused = false
    }

    fun closeTab(id: Int) {
        if (tabIds.size == 1) return
        val index = tabIds.indexOf(id)
        tabIds = tabIds - id
        pendingStarts = pendingStarts - id
        if (activeTab == id) activeTab = tabIds[(index - 1).coerceAtLeast(0)]
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= DUAL_PANE_MIN_WIDTH
        val dual = wide && leftState.prefs.dualPane
        val right = if (dual) viewModel<BrowserViewModel>(key = RIGHT_PANE_KEY) else null
        val rightInVault = right?.state?.collectAsStateWithLifecycle()?.value?.inVault == true
        // One window, one FLAG_SECURE: secure if any visible pane shows the vault.
        SecureWindow(appLocked || leftState.inVault || rightInVault)

        val actions = TabActions(
            onNewTab = ::openTab,
            onCloseTab = if (tabIds.size > 1) ({ closeTab(activeTab) }) else null,
            dualPane = dual.takeIf { wide },
            onToggleDualPane = { active.updatePrefs { it.copy(dualPane = !it.dualPane) } },
        )
        val leftPane = @Composable { modifier: Modifier ->
            val start = pendingStarts[activeTab]
            LaunchedEffect(activeTab) {
                active.startAt(start)
                if (start != null) pendingStarts = pendingStarts - activeTab
            }
            // Per-tab UI state (search text, drawer, scroll) must not carry over to the next tab.
            Box(modifier.onAnyTouch { rightFocused = false }) {
                key(activeTab) {
                    BrowserScreen(
                        onEditFile = onEditFile,
                        onOpenSettings = onOpenSettings,
                        onOpenRecycleBin = onOpenRecycleBin,
                        viewModel = active,
                        isPrimary = true,
                        isFocused = !dual || !rightFocused,
                        tabs = actions,
                        tabStrip = {
                            if (tabIds.size > 1) {
                                TabStrip(tabIds, activeTab, tabModels, onSelect = { activeTab = it }, onClose = ::closeTab)
                            }
                        },
                    )
                }
            }
        }

        if (right == null) {
            leftPane(Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize()) {
                leftPane(Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight().onAnyTouch { rightFocused = true }) {
                    BrowserScreen(
                        onEditFile = onEditFile,
                        onOpenSettings = onOpenSettings,
                        onOpenRecycleBin = onOpenRecycleBin,
                        viewModel = right,
                        isPrimary = false,
                        isFocused = rightFocused,
                        tabs = actions.copyForRightPane(),
                    )
                }
            }
        }
    }
}

/** The right pane has no tabs of its own: "new tab" opens in the left pane's tab row. */
private fun TabActions.copyForRightPane() = TabActions(onNewTab, onCloseTab = null, dualPane, onToggleDualPane)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabStrip(
    tabIds: List<Int>,
    activeTab: Int,
    models: Map<Int, BrowserViewModel>,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
) {
    PrimaryScrollableTabRow(selectedTabIndex = tabIds.indexOf(activeTab), edgePadding = 8.dp) {
        tabIds.forEach { id ->
            val state by models.getValue(id).state.collectAsStateWithLifecycle()
            val title = state.breadcrumbs.lastOrNull()?.label.orEmpty()
            Tab(
                selected = id == activeTab,
                onClick = { onSelect(id) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        IconButton(onClick = { onClose(id) }, modifier = Modifier.padding(start = 4.dp).size(24.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_close_tab),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }
    }
}

/** Notices touches anywhere inside without taking them away from the content. */
private fun Modifier.onAnyTouch(onTouch: () -> Unit) = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            onTouch()
        }
    }
}

private fun tabKey(id: Int) = "browser-tab-$id"

private const val RIGHT_PANE_KEY = "browser-pane-right"

private val PendingStartsSaver = listSaver<Map<Int, Location>, String>(
    save = { map -> map.flatMap { (id, location) -> listOf(id.toString(), location.fsId, location.path) } },
    restore = { list -> list.chunked(3).associate { (id, fsId, path) -> id.toInt() to Location(fsId, path) } },
)

