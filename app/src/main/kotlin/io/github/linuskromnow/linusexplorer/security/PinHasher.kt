package io.github.linuskromnow.linusexplorer.security

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Salted PBKDF2-HMAC-SHA256 hashes for the app-lock PIN, stored as "pbkdf2-sha256$iterations$salt$hash". */
object PinHasher {
    private const val SCHEME = "pbkdf2-sha256"
    const val ITERATIONS = 150_000

    fun hash(pin: String, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val encoder = Base64.getEncoder().withoutPadding()
        return listOf(SCHEME, iterations.toString(), encoder.encodeToString(salt), encoder.encodeToString(derive(pin, salt, iterations)))
            .joinToString("$")
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != SCHEME) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val decoder = Base64.getDecoder()
        val expected = decoder.decode(parts[3])
        return MessageDigest.isEqual(derive(pin, decoder.decode(parts[2]), iterations), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(pin.toByteArray(), salt, iterations)
        return (generator.generateDerivedParameters(256) as KeyParameter).key
    }
}

/** Slows down PIN guessing: free attempts first, then exponentially growing waits (capped at 1 h). */
object LockoutPolicy {
    const val FREE_ATTEMPTS = 5
    private const val BASE_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 3_600_000L

    /** How long input is blocked after [failures] consecutive wrong PINs. */
    fun delayAfter(failures: Int, freeAttempts: Int = FREE_ATTEMPTS, baseDelayMs: Long = BASE_DELAY_MS): Long {
        val excess = failures - freeAttempts
        if (excess < 0) return 0
        return (baseDelayMs shl minOf(excess, 7)).coerceAtMost(MAX_DELAY_MS)
    }
}
