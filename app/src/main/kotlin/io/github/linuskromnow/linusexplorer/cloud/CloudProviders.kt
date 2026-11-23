package io.github.linuskromnow.linusexplorer.cloud

import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.fs.network.Protocol
import io.github.linuskromnow.linusexplorer.fs.network.cloud.OAuthEndpoints
import io.github.linuskromnow.linusexplorer.fs.network.dropbox.Dropbox
import io.github.linuskromnow.linusexplorer.fs.network.onedrive.OneDrive
import okhttp3.OkHttpClient

/** What the sign-in flow and the connection dialog need to know about each cloud provider. */
class CloudProvider(
    val endpoints: OAuthEndpoints,
    /** LinusExplorer's own registered app; empty until registered (users then bring their own). */
    val defaultClientId: String,
    /** Stored in [io.github.linuskromnow.linusexplorer.fs.network.Connection.host]; only for display. */
    val host: String,
    val clientIdLabel: Int,
    /** Explains how to register one's own app; takes the redirect URI as its argument. */
    val clientIdHelp: Int,
    /** The account's e-mail address for the connection label. Blocking. */
    val accountName: (OkHttpClient, accessToken: String) -> String,
)

object CloudProviders {
    private val dropbox = CloudProvider(
        endpoints = Dropbox.ENDPOINTS,
        defaultClientId = CloudKeys.DROPBOX_APP_KEY,
        host = "dropbox.com",
        clientIdLabel = R.string.connection_app_key,
        clientIdHelp = R.string.connection_app_key_help,
        accountName = { client, token -> Dropbox.accountEmail(client, token) },
    )

    private val oneDrive = CloudProvider(
        endpoints = OneDrive.ENDPOINTS,
        defaultClientId = CloudKeys.ONEDRIVE_CLIENT_ID,
        host = "onedrive.live.com",
        clientIdLabel = R.string.connection_client_id,
        clientIdHelp = R.string.connection_client_id_help_onedrive,
        accountName = { client, token -> OneDrive.accountName(client, token) },
    )

    /** Null for protocols that are not cloud accounts. */
    fun of(protocol: Protocol): CloudProvider? = when (protocol) {
        Protocol.DROPBOX -> dropbox
        Protocol.ONEDRIVE -> oneDrive
        else -> null
    }
}
