package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.FsException
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Trust-on-first-use store for server keys (SSH host keys, later TLS certificates), keyed by
 * "host:port". A key that differs from the stored one is reported as changed, never silently accepted.
 */
class KnownHosts(private val file: File) {
    private val entries: MutableMap<String, String> = load()

    enum class Verdict { TRUSTED, UNKNOWN, CHANGED }

    @Synchronized
    fun check(host: String, port: Int, key: ByteArray): Verdict {
        val stored = entries[endpoint(host, port)] ?: return Verdict.UNKNOWN
        return if (stored == encode(key)) Verdict.TRUSTED else Verdict.CHANGED
    }

    @Synchronized
    fun trust(host: String, port: Int, key: ByteArray) {
        entries[endpoint(host, port)] = encode(key)
        file.parentFile?.mkdirs()
        file.writeText(entries.entries.joinToString("") { "${it.key} ${it.value}\n" })
    }

    @Synchronized
    fun forget(host: String, port: Int) {
        if (entries.remove(endpoint(host, port)) != null) {
            file.writeText(entries.entries.joinToString("") { "${it.key} ${it.value}\n" })
        }
    }

    private fun load(): MutableMap<String, String> =
        if (!file.exists()) {
            HashMap()
        } else {
            file.readLines().filter { ' ' in it }.associateTo(HashMap()) { it.substringBefore(' ') to it.substringAfter(' ') }
        }

    private fun endpoint(host: String, port: Int) = "${host.lowercase()}:$port"

    private fun encode(key: ByteArray) = Base64.getEncoder().encodeToString(key)

    companion object {
        /** OpenSSH-style "SHA256:..." fingerprint, so users can compare it with `ssh-keygen -lf`. */
        fun fingerprint(key: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    }
}

/**
 * The server presented a key we have not trusted yet ([changed] = false) or one that differs from
 * the trusted key ([changed] = true, a possible attack). The UI asks the user, then calls
 * [KnownHosts.trust] with [key] and retries.
 */
class UntrustedHostKeyException(
    val fsId: String,
    val host: String,
    val port: Int,
    val key: ByteArray,
    val changed: Boolean,
) : FsException(if (changed) "The server key of $host changed" else "Unknown server key for $host") {
    val fingerprint: String get() = KnownHosts.fingerprint(key)
}
