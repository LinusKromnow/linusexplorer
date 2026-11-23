package io.github.linuskromnow.linusexplorer.fs.network.onedrive

import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.network.cloud.OAuthEndpoints
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Microsoft identity platform sign-in for OneDrive ("common": personal and work/school accounts). */
object OneDrive {
    private const val LOGIN = "https://login.microsoftonline.com/common/oauth2/v2.0/"

    /**
     * Files.ReadWrite covers the user's own OneDrive and needs no admin consent; offline_access
     * yields a refresh token (rotated on every use); User.Read gives the account name for the label.
     */
    fun endpoints(tokenUrl: HttpUrl = "${LOGIN}token".toHttpUrl()) = OAuthEndpoints(
        service = "OneDrive",
        authorizeUrl = "${LOGIN}authorize".toHttpUrl(),
        tokenUrl = tokenUrl,
        scopes = "Files.ReadWrite User.Read offline_access",
        extraAuthorizeParams = mapOf("prompt" to "select_account"),
        redirectName = "onedrive",
    )

    val ENDPOINTS = endpoints()

    /** The account's e-mail address (or sign-in name), shown in the connection's label. Blocking. */
    fun accountName(client: OkHttpClient, accessToken: String, graphBase: HttpUrl = OneDriveFileSystem.GRAPH_BASE): String {
        val url = graphBase.newBuilder().addPathSegment("me").addQueryParameter("\$select", "mail,userPrincipalName").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw FsException("OneDrive: HTTP ${response.code}")
            val json = JSONObject(response.body.string())
            return json.optString("mail").takeIf { it.isNotEmpty() && it != "null" } ?: json.optString("userPrincipalName")
        }
    }
}
