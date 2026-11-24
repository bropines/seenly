package io.github.bropines.lens

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import io.github.bropines.lens.app.data.SecretStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets encrypted at rest: AES-256-GCM with a key that lives in the Android
 * Keystore and never leaves it, so the files are useless off this device
 * (a backup, a copied data directory). Platform APIs only, so both flavors
 * have it.
 *
 * A file is the 12-byte nonce GCM chose, then the ciphertext with its tag.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    override val encrypted: Boolean = true

    // Outside the system's backups: the key stays on this device, so a restored file could not be read anyway.
    private val dir = File(context.noBackupFilesDir, "secrets")

    private fun file(name: String) = File(dir, "$name.bin")

    override fun read(name: String): String? {
        val bytes = file(name).takeIf { it.isFile }?.readBytes() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
            cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES).decodeToString()
        } catch (e: Exception) {
            // The key is gone (the app's data was restored onto another device) or the file is
            // damaged: either way the secret is lost, and its owner will be asked for it again.
            Log.w(TAG, "secret $name cannot be read", e)
            null
        }
    }

    override fun write(name: String, value: String?) {
        if (value == null) {
            file(name).delete()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        dir.mkdirs()
        // Whole or not at all: a half-written file would read as a lost secret.
        val partial = File(dir, "$name.tmp")
        partial.writeBytes(cipher.iv + cipher.doFinal(value.encodeToByteArray()))
        if (!partial.renameTo(file(name))) {
            file(name).delete()
            partial.renameTo(file(name))
        }
    }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keystore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val TAG = "SecretStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "seenly-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
