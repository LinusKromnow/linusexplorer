package io.github.linuskromnow.linusexplorer.fs.network

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * System CA validation first; a certificate the user explicitly trusted (self-signed home servers)
 * is accepted as well. Anything else is rejected and remembered in [rejected] for the trust prompt.
 */
@SuppressLint("CustomX509TrustManager")
class TofuTrustManager(
    private val knownHosts: KnownHosts,
    private val host: String,
    private val port: Int,
) : X509TrustManager {
    @Volatile var rejected: Pair<ByteArray, Boolean>? = null

    private val platform: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    fun isPinned(cert: ByteArray) = knownHosts.check(host, port, cert) == KnownHosts.Verdict.TRUSTED

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val cert = chain.firstOrNull()?.encoded ?: throw CertificateException("No server certificate")
        if (isPinned(cert)) return
        try {
            platform.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            rejected = cert to (knownHosts.check(host, port, cert) == KnownHosts.Verdict.CHANGED)
            throw e
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("Client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}

/** Uses [trust] for TLS; a pinned certificate also passes the hostname check (it was trusted for this host). */
fun OkHttpClient.Builder.trustOnFirstUse(trust: TofuTrustManager): OkHttpClient.Builder {
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    return sslSocketFactory(context.socketFactory, trust)
        .hostnameVerifier { hostname, session ->
            HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session) ||
                (session.peerCertificates.firstOrNull()?.encoded?.let { trust.isPinned(it) } == true)
        }
}
