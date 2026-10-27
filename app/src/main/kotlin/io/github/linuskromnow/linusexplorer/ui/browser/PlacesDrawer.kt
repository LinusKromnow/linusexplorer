package io.github.linuskromnow.linusexplorer.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.data.Bookmark
import io.github.linuskromnow.linusexplorer.fs.DrawerSection
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.network.Connection
import io.github.linuskromnow.linusexplorer.fs.root.RootFileSystem
import io.github.linuskromnow.linusexplorer.storage.Place

@Composable
fun PlacesDrawer(
    places: List<Place>,
    currentPath: String?,
    vaultSelected: Boolean,
    onPlaceClick: (Place) -> Unit,
    onVaultClick: () -> Unit,
    connections: List<Connection>,
    currentFsId: String,
    onConnectionClick: (Connection) -> Unit,
    onEditConnection: (Connection?) -> Unit,
    onSettingsClick: () -> Unit,
    bookmarks: List<Bookmark>,
    recents: List<Location>,
    currentLocation: Location,
    describe: (Location) -> FolderLabel,
    onFolderClick: (Location) -> Unit,
    onRemoveBookmark: (Bookmark) -> Unit,
    onClearRecents: () -> Unit,
    /** Null hides the entry (recycle bin switched off). */
    onRecycleBinClick: (() -> Unit)?,
    /** Null hides the entry (root access switched off). */
    onRootClick: (() -> Unit)?,
    collapsed: Set<DrawerSection>,
    onToggleSection: (DrawerSection) -> Unit,
) {
    @Composable
    fun Section(
        section: DrawerSection,
        title: String,
        divider: Boolean = true,
        action: (@Composable () -> Unit)? = null,
        content: @Composable () -> Unit,
    ) = CollapsibleSection(title, expanded = section !in collapsed, { onToggleSection(section) }, divider, action, content)

    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
            )
            Section(DrawerSection.STORAGE, stringResource(R.string.drawer_storage), divider = false) {
                places.filter { it.kind != Place.Kind.FOLDER }.forEach { PlaceItem(it, currentPath, onPlaceClick) }
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.vault_name)) },
                    icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    selected = vaultSelected,
                    onClick = onVaultClick,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                if (onRootClick != null) {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.place_superuser)) },
                        icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = null) },
                        selected = currentFsId == RootFileSystem.ID,
                        onClick = onRootClick,
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }

            if (bookmarks.isNotEmpty()) {
                Section(DrawerSection.BOOKMARKS, stringResource(R.string.drawer_bookmarks)) {
                    bookmarks.forEach { bookmark ->
                        FolderItem(
                            label = describe(bookmark.location).copy(title = bookmark.name),
                            icon = Icons.Default.Bookmark,
                            selected = bookmark.location == currentLocation,
                            onClick = { onFolderClick(bookmark.location) },
                            badge = {
                                IconButton(onClick = { onRemoveBookmark(bookmark) }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_remove_bookmark))
                                }
                            },
                        )
                    }
                }
            }

            // The folder on screen is always the newest entry; listing it would only repeat the title bar.
            val otherRecents = recents.filter { it != currentLocation }
            if (otherRecents.isNotEmpty()) {
                Section(
                    DrawerSection.RECENT,
                    stringResource(R.string.drawer_recent),
                    action = {
                        IconButton(onClick = onClearRecents) {
                            Icon(Icons.Default.ClearAll, contentDescription = stringResource(R.string.action_clear_recent))
                        }
                    },
                ) {
                    otherRecents.forEach { location ->
                        FolderItem(describe(location), Icons.Default.History, selected = false, onClick = { onFolderClick(location) })
                    }
                }
            }

            Section(DrawerSection.NETWORK, stringResource(R.string.drawer_network)) {
                connections.forEach { connection ->
                    NavigationDrawerItem(
                        label = { Text(connection.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = { Icon(Icons.Default.Dns, contentDescription = null) },
                        badge = {
                            IconButton(onClick = { onEditConnection(connection) }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.connection_edit))
                            }
                        },
                        selected = currentFsId == connection.fsId,
                        onClick = { onConnectionClick(connection) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.connection_add)) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    selected = false,
                    onClick = { onEditConnection(null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }

            val folders = places.filter { it.kind == Place.Kind.FOLDER }
            if (folders.isNotEmpty()) {
                Section(DrawerSection.FOLDERS, stringResource(R.string.drawer_folders)) {
                    folders.forEach { PlaceItem(it, currentPath, onPlaceClick) }
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
            if (onRecycleBinClick != null) {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.recycle_bin_title)) },
                    icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    selected = false,
                    onClick = onRecycleBinClick,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.settings_title)) },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                selected = false,
                onClick = onSettingsClick,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}

/** A drawer section whose header folds its entries away; [action] sits next to the chevron. */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    divider: Boolean,
    action: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (divider) HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
    val state = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .padding(start = 28.dp, end = 16.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        if (expanded) action?.invoke()
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
    AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
        Column { content() }
    }
}

@Composable
private fun FolderItem(
    label: FolderLabel,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    badge: (@Composable () -> Unit)? = null,
) {
    NavigationDrawerItem(
        label = {
            Column {
                Text(label.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (label.subtitle.isNotEmpty()) {
                    Text(
                        label.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        icon = { Icon(icon, contentDescription = null) },
        badge = badge,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

@Composable
private fun PlaceItem(place: Place, currentPath: String?, onClick: (Place) -> Unit) {
    NavigationDrawerItem(
        label = { Text(place.label) },
        icon = { Icon(place.kind.icon(), contentDescription = null) },
        selected = place.path == currentPath,
        onClick = { onClick(place) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

private fun Place.Kind.icon(): ImageVector = when (this) {
    Place.Kind.INTERNAL -> Icons.Default.PhoneAndroid
    Place.Kind.REMOVABLE -> Icons.Default.SdCard
    Place.Kind.ROOT -> Icons.Default.AccountTree
    Place.Kind.FOLDER -> Icons.Default.Folder
}
