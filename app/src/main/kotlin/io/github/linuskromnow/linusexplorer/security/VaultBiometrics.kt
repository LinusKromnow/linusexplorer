package io.github.linuskromnow.linusexplorer.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fingerprint unlock for the vault: a copy of the vault's master key is encrypted with an Android
 * Keystore key that can only be used right after a strong biometric authentication. Enrolling a
 * new fingerprint invalidates that key, after which the vault password is needed again.
 */
class VaultBiometrics(private val wrappedKeyFile: File) {

    val isEnabled: Boolean get() = wrappedKeyFile.exists()

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Asks for a fingerprint, then stores [masterKey] protected by it. */
    fun enable(activity: FragmentActivity, masterKey: ByteArray, prompt: Prompt, onEnabled: () -> Unit) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, createKey()) }
        authenticate(activity, cipher, prompt) { authorized ->
            val sealed = authorized.doFinal(masterKey)
            wrappedKeyFile.writeBytes(byteArrayOf(authorized.iv.size.toByte()) + authorized.iv + sealed)
            masterKey.fill(0)
            onEnabled()
        }
    }

    /**
     * Asks for a fingerprint and hands the recovered master key to [onKey]. If the Keystore key
     * was invalidated (new fingerprint enrolled), fingerprint unlock is turned off and
     * [onInvalidated] runs instead.
     */
    fun unlock(activity: FragmentActivity, prompt: Prompt, onKey: (ByteArray) -> Unit, onInvalidated: () -> Unit) {
        val data = wrappedKeyFile.readBytes()
        val ivSize = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + ivSize)
        val sealed = data.copyOfRange(1 + ivSize, data.size)
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
        val cipher = try {
            key ?: throw KeyPermanentlyInvalidatedException()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable()
            onInvalidated()
            return
        }
        authenticate(activity, cipher, prompt) { authorized -> onKey(authorized.doFinal(sealed)) }
    }

    fun disable() {
        wrappedKeyFile.delete()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    data class Prompt(val title: String, val negativeButton: String)

    private fun authenticate(activity: FragmentActivity, cipher: Cipher, prompt: Prompt, onAuthorized: (Cipher) -> Unit) {
        val biometricPrompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(onAuthorized)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(prompt.title)
            .setNegativeButtonText(prompt.negativeButton)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        biometricPrompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun createKey(): SecretKey {
        keyStore().deleteEntry(KEY_ALIAS)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) // every use needs a fresh scan
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "linusexplorer_vault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
