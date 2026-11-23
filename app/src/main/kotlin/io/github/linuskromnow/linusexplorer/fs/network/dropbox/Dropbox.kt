package io.github.linuskromnow.linusexplorer.fs.network.dropbox

import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.network.cloud.OAuthEndpoints
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Dropbox's sign-in and account endpoints. */
object Dropbox {
    /**
     * Scopes come from the app's registration in the Dropbox App Console.
     * `token_access_type=offline` asks for a refresh token; Dropbox does not rotate it.
     */
    fun endpoints(tokenUrl: HttpUrl = "https://api.dropboxapi.com/oauth2/token".toHttpUrl()) = OAuthEndpoints(
        service = "Dropbox",
        authorizeUrl = "https://www.dropbox.com/oauth2/authorize".toHttpUrl(),
        tokenUrl = tokenUrl,
        extraAuthorizeParams = mapOf("token_access_type" to "offline"),
        redirectName = "dropbox",
    )

    val ENDPOINTS = endpoints()

    /** The signed-in account's e-mail address, shown in the connection's label. Blocking. */
    fun accountEmail(client: OkHttpClient, accessToken: String, apiBase: HttpUrl = DropboxFileSystem.API_BASE): String {
        val request = Request.Builder()
            .url(apiBase.resolve("users/get_current_account")!!)
            .header("Authorization", "Bearer $accessToken")
            .post("null".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw FsException("Dropbox: HTTP ${response.code}")
            return JSONObject(response.body.string()).optString("email")
        }
    }
}
