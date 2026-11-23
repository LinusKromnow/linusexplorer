package io.github.linuskromnow.linusexplorer.fs.network.dropbox

import io.github.linuskromnow.linusexplorer.fs.FsException
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Dropbox sign-in: OAuth 2 authorization code flow with PKCE, so the app needs no client secret
 * and the browser returns to [redirectUri]. Only the long-lived refresh token is stored; access
 * tokens (valid ~4 hours) are fetched with it as needed.
 */
object DropboxOAuth {
    /**
     * Where the browser returns to; must be registered under "Redirect URIs" in the Dropbox App
     * Console, exactly like this. [appId] is the application ID (debug builds have their own).
     */
    fun redirectUri(appId: String) = "$appId://oauth/dropbox"

    val AUTHORIZE_URL = "https://www.dropbox.com/oauth2/authorize".toHttpUrl()
    val TOKEN_URL = "https://api.dropboxapi.com/oauth2/token".toHttpUrl()

    /** A random PKCE code verifier (43-128 characters of base64url). */
    fun newVerifier(): String = base64Url(ByteArray(48).also { SecureRandom().nextBytes(it) })

    fun newState(): String = base64Url(ByteArray(18).also { SecureRandom().nextBytes(it) })

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** The page the user signs in on. `token_access_type=offline` asks for a refresh token. */
    fun authorizeUrl(appKey: String, redirectUri: String, verifier: String, state: String, base: HttpUrl = AUTHORIZE_URL): HttpUrl =
        base.newBuilder()
            .addQueryParameter("client_id", appKey)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("code_challenge", challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("token_access_type", "offline")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .build()

    class Tokens(val accessToken: String, val refreshToken: String?, val expiresAtMillis: Long)

    /** Trades the code from the redirect for tokens. Blocking. */
    fun exchangeCode(
        client: OkHttpClient,
        appKey: String,
        redirectUri: String,
        code: String,
        verifier: String,
        tokenUrl: HttpUrl = TOKEN_URL,
    ): Tokens =
        token(
            client,
            tokenUrl,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", appKey)
                .add("code_verifier", verifier)
                .add("redirect_uri", redirectUri)
                .build(),
        )

    /** A fresh access token. Throws [SignedOutFsException] if the user revoked the app. Blocking. */
    fun refresh(client: OkHttpClient, appKey: String, refreshToken: String, tokenUrl: HttpUrl = TOKEN_URL): Tokens =
        token(
            client,
            tokenUrl,
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", appKey)
                .build(),
        )

    private fun token(client: OkHttpClient, url: HttpUrl, form: FormBody): Tokens {
        val started = System.currentTimeMillis()
        val response = try {
            client.newCall(Request.Builder().url(url).post(form).build()).execute()
        } catch (e: IOException) {
            throw FsException("Cannot reach Dropbox: ${e.message ?: e.javaClass.simpleName}", e)
        }
        response.use {
            val body = it.body.string()
            val json = try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw FsException("Dropbox sign-in failed: HTTP ${it.code}")
            }
            if (!it.isSuccessful) {
                // invalid_grant: the refresh token was revoked (app removed in Dropbox settings) or the code expired.
                if (json.optString("error") == "invalid_grant") throw SignedOutFsException()
                throw FsException("Dropbox sign-in failed: ${json.optString("error_description").ifEmpty { json.optString("error") }}")
            }
            return Tokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifEmpty { null },
                expiresAtMillis = started + json.optLong("expires_in", 3600) * 1000,
            )
        }
    }

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** The stored Dropbox sign-in no longer works; the user has to sign in again. */
class SignedOutFsException : FsException("Signed out of Dropbox. Edit the connection and sign in again.")

/** Account calls that are not about files. */
object DropboxApi {
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
