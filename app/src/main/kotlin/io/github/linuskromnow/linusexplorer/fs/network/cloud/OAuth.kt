package io.github.linuskromnow.linusexplorer.fs.network.cloud

import io.github.linuskromnow.linusexplorer.fs.FsException
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * One cloud provider's OAuth 2 endpoints. Sign-in is the authorization code flow with PKCE, so
 * the app needs no client secret; only the refresh token is stored.
 */
class OAuthEndpoints(
    /** Shown in messages, e.g. "Dropbox". */
    val service: String,
    val authorizeUrl: HttpUrl,
    val tokenUrl: HttpUrl,
    /** Space-separated scopes; empty to get whatever the app was registered with. */
    val scopes: String = "",
    /** Provider-specific extras for the sign-in page, e.g. Dropbox's `token_access_type=offline`. */
    val extraAuthorizeParams: Map<String, String> = emptyMap(),
    /** Last part of the redirect URI, e.g. "dropbox" in `<appId>://oauth/dropbox`. */
    val redirectName: String,
) {
    /**
     * Where the browser returns to; must be registered with the provider exactly like this.
     * [appId] is the application ID (debug builds have their own).
     */
    fun redirectUri(appId: String) = "$appId://oauth/$redirectName"
}

class OAuthTokens(val accessToken: String, val refreshToken: String?, val expiresAtMillis: Long)

object OAuth {
    /** A random PKCE code verifier (43-128 characters of base64url). */
    fun newVerifier(): String = base64Url(ByteArray(48).also { SecureRandom().nextBytes(it) })

    fun newState(): String = base64Url(ByteArray(18).also { SecureRandom().nextBytes(it) })

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** The page the user signs in on. */
    fun authorizeUrl(endpoints: OAuthEndpoints, clientId: String, redirectUri: String, verifier: String, state: String): HttpUrl =
        endpoints.authorizeUrl.newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("code_challenge", challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .apply {
                if (endpoints.scopes.isNotEmpty()) addQueryParameter("scope", endpoints.scopes)
                endpoints.extraAuthorizeParams.forEach { (key, value) -> addQueryParameter(key, value) }
            }
            .build()

    /** Trades the code from the redirect for tokens. Blocking. */
    fun exchangeCode(
        client: OkHttpClient,
        endpoints: OAuthEndpoints,
        clientId: String,
        redirectUri: String,
        code: String,
        verifier: String,
    ): OAuthTokens = token(
        client,
        endpoints,
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .add("redirect_uri", redirectUri)
            .apply { if (endpoints.scopes.isNotEmpty()) add("scope", endpoints.scopes) }
            .build(),
    )

    /** A fresh access token (and, with some providers, a new refresh token). Blocking. */
    fun refresh(client: OkHttpClient, endpoints: OAuthEndpoints, clientId: String, refreshToken: String): OAuthTokens = token(
        client,
        endpoints,
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .apply { if (endpoints.scopes.isNotEmpty()) add("scope", endpoints.scopes) }
            .build(),
    )

    private fun token(client: OkHttpClient, endpoints: OAuthEndpoints, form: FormBody): OAuthTokens {
        val started = System.currentTimeMillis()
        val response = try {
            client.newCall(Request.Builder().url(endpoints.tokenUrl).post(form).build()).execute()
        } catch (e: IOException) {
            throw FsException("Cannot reach ${endpoints.service}: ${e.message ?: e.javaClass.simpleName}", e)
        }
        response.use {
            val json = try {
                JSONObject(it.body.string())
            } catch (e: JSONException) {
                throw FsException("${endpoints.service} sign-in failed: HTTP ${it.code}")
            }
            if (!it.isSuccessful) {
                // invalid_grant: the refresh token was revoked or expired, or the code was already used.
                if (json.optString("error") == "invalid_grant") throw SignedOutFsException(endpoints.service)
                val reason = json.optString("error_description").ifEmpty { json.optString("error") }.lineSequence().first()
                throw FsException("${endpoints.service} sign-in failed: $reason")
            }
            return OAuthTokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifEmpty { null },
                expiresAtMillis = started + json.optLong("expires_in", 3600) * 1000,
            )
        }
    }

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Hands out access tokens for one signed-in account, refreshing them shortly before they expire.
 * Providers that rotate refresh tokens (Microsoft) report the new one through [onRefreshTokenChanged],
 * which must store it: the old one stops working.
 */
class OAuthSession(
    private val client: OkHttpClient,
    private val endpoints: OAuthEndpoints,
    private val clientId: String,
    @Volatile private var refreshToken: String?,
    private val onRefreshTokenChanged: (String) -> Unit = {},
) {
    private var accessToken: String? = null
    private var expiresAt = 0L

    @Synchronized
    fun token(forceRefresh: Boolean = false): String {
        val current = accessToken
        if (!forceRefresh && current != null && System.currentTimeMillis() < expiresAt - 60_000) return current
        val refresh = refreshToken ?: throw SignedOutFsException(endpoints.service)
        val tokens = OAuth.refresh(client, endpoints, clientId, refresh)
        accessToken = tokens.accessToken
        expiresAt = tokens.expiresAtMillis
        val rotated = tokens.refreshToken
        if (rotated != null && rotated != refresh) {
            refreshToken = rotated
            onRefreshTokenChanged(rotated)
        }
        return tokens.accessToken
    }
}

/** The stored sign-in no longer works; the user has to sign in again. */
class SignedOutFsException(service: String) :
    FsException("Signed out of $service. Edit the connection and sign in again.")
