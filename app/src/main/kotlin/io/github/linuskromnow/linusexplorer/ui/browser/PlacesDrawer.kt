package io.github.linuskromnow.linusexplorer.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.storage.Place

@Composable
fun PlacesDrawer(places: List<Place>, currentPath: String?, onPlaceClick: (Place) -> Unit) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
            )
            SectionHeader(stringResource(R.string.drawer_storage))
            places.filter { it.kind != Place.Kind.FOLDER }.forEach { PlaceItem(it, currentPath, onPlaceClick) }

            val folders = places.filter { it.kind == Place.Kind.FOLDER }
            if (folders.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                SectionHeader(stringResource(R.string.drawer_folders))
                folders.forEach { PlaceItem(it, currentPath, onPlaceClick) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
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
