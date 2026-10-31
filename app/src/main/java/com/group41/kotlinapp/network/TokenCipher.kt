package com.group41.kotlinapp.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts token values; the encryption key stays in Android Keystore, outside preferences. */
internal class TokenCipher {
    private fun key(createIfMissing: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!createIfMissing) throw KeyStoreException("Token encryption key is unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    fun encrypt(value: String, preferenceKey: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Let the provider generate a fresh IV for every write, even for the same token.
        cipher.init(Cipher.ENCRYPT_MODE, key(createIfMissing = true))
        cipher.updateAAD(preferenceKey.toByteArray(Charsets.UTF_8))
        return "v1:${encode(cipher.iv)}:${encode(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))}"
    }

    fun decrypt(value: String, preferenceKey: String): String {
        val parts = value.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "Invalid encrypted token format" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        require(iv.size == 12) { "Invalid token IV" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(createIfMissing = false), GCMParameterSpec(128, iv))
        // Binding the field name prevents swapping an access token with a refresh token.
        cipher.updateAAD(preferenceKey.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEY_ALIAS = "com.group41.kotlinapp.auth.tokens.v1"
    }
}
