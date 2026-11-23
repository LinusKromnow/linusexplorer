package io.github.linuskromnow.linusexplorer.cloud

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.fs.network.Connection
import io.github.linuskromnow.linusexplorer.fs.network.NetworkFileSystems
import io.github.linuskromnow.linusexplorer.fs.network.dropbox.DropboxApi
import io.github.linuskromnow.linusexplorer.fs.network.dropbox.DropboxOAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Browser-based sign-in for cloud accounts. [start] opens the provider's page; the provider sends
 * the browser to [DropboxOAuth.redirectUri], which [OAuthRedirectActivity] hands to [complete].
 * The finished connection is saved and reported through [results].
 */
class CloudSignIn(private val network: NetworkFileSystems, private val scope: CoroutineScope) {
    sealed interface Result {
        data class Success(val connection: Connection) : Result

        data class Failure(val resId: Int, val args: List<Any> = emptyList()) : Result
    }

    private class Pending(val template: Connection, val redirectUri: String, val verifier: String, val state: String)

    /** Kept in memory only: if Android ends the app while the browser is open, the user simply retries. */
    @Volatile private var pending: Pending? = null

    private val _results = Channel<Result>(Channel.BUFFERED)

    /** Each result is delivered once, to whichever screen collects it first. */
    val results: Flow<Result> = _results.receiveAsFlow()

    /** Opens the sign-in page for [template] (its [Connection.share] is the app key). False if no browser is installed. */
    fun start(context: Context, template: Connection): Boolean {
        val verifier = DropboxOAuth.newVerifier()
        val state = DropboxOAuth.newState()
        val redirectUri = DropboxOAuth.redirectUri(context.packageName)
        val url = DropboxOAuth.authorizeUrl(template.share, redirectUri, verifier, state)
        pending = Pending(template, redirectUri, verifier, state)
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
            true
        } catch (e: ActivityNotFoundException) {
            pending = null
            false
        }
    }

    fun complete(uri: Uri) {
        val request = pending
        pending = null
        val code = uri.getQueryParameter("code")
        when {
            request == null || uri.getQueryParameter("state") != request.state ->
                _results.trySend(Result.Failure(R.string.msg_sign_in_interrupted))
            code == null ->
                // "access_denied" when the user pressed Cancel on the provider's page.
                if (uri.getQueryParameter("error") == "access_denied") {
                    _results.trySend(Result.Failure(R.string.msg_sign_in_cancelled))
                } else {
                    _results.trySend(Result.Failure(R.string.msg_sign_in_failed, listOf(uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error").orEmpty())))
                }
            else -> scope.launch {
                val result = try {
                    val client = network.httpClient
                    val appKey = request.template.share
                    val tokens = DropboxOAuth.exchangeCode(client, appKey, request.redirectUri, code, request.verifier)
                    val refreshToken = tokens.refreshToken ?: error("Dropbox sent no refresh token")
                    val email = runCatching { DropboxApi.accountEmail(client, tokens.accessToken) }.getOrDefault("")
                    val connection = request.template.copy(password = refreshToken, username = email)
                    network.save(connection)
                    Result.Success(connection)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.Failure(R.string.msg_sign_in_failed, listOf(e.message ?: e.javaClass.simpleName))
                }
                _results.send(result)
            }
        }
    }
}
