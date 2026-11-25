package com.group41.kotlinapp.network

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Stores encrypted session tokens and exposes changes to the signed-in account. */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val encryptedPrefs = context.getSharedPreferences("auth_tokens", Context.MODE_PRIVATE)
    private val cipher = TokenCipher()

    init {
        synchronized(lock) {
            // Existing installations have plaintext tokens in "auth". Commit encrypted
            // copies first, then remove the originals. If saving fails, keep the old
            // session intact and report the failure rather than falling back to plaintext.
            val editor = encryptedPrefs.edit()
            var hasLegacyTokens = false
            for (name in listOf(ACCESS, REFRESH)) {
                prefs.getString(name, null)?.let { token ->
                    hasLegacyTokens = true
                    // A previous migration may have finished copying before interruption.
                    if (!encryptedPrefs.contains(name)) editor.putString(name, cipher.encrypt(token, name))
                }
            }
            if (hasLegacyTokens) {
                if (!editor.commit()) throw IOException("Unable to persist encrypted tokens")
                if (!prefs.edit().remove(ACCESS).remove(REFRESH).commit()) {
                    throw IOException("Unable to remove legacy tokens")
                }
            }
        }
    }

    var access: String?
        get() = readToken(ACCESS)
        set(value) = writeToken(ACCESS, value)

    var refresh: String?
        get() = readToken(REFRESH)
        set(value) = writeToken(REFRESH, value)

    /** id del usuario en el backend; analytics lo necesita desde el arranque. */
    var userId: String?
        get() = prefs.getString("user_id", null)
        set(value) = synchronized(lock) {
            prefs.edit { putString("user_id", value) }
            sessionChanges.update { it + 1 }
        }

    /** Emits the current account and updates after sign-in, account changes, and sign-out. */
    internal val userIds: Flow<String?> = sessionChanges
        .map { userId }
        .distinctUntilChanged()

    /** El servidor guarda el token de Google; aquí solo la bandera para la UI */
    var googleConnected: Boolean
        get() = prefs.getBoolean("google_connected", false)
        set(value) = prefs.edit { putBoolean("google_connected", value) }

    // Read the account and token under the same lock so a concurrent sign-in
    // or sign-out cannot pair one user's ID with another user's token.
    internal fun accessFor(account: EnrollmentAccount): String? = synchronized(lock) {
        if (userId == account.userId) access else null
    }

    internal fun refreshFor(account: EnrollmentAccount): String? = synchronized(lock) {
        if (userId == account.userId) refresh else null
    }

    // A delayed refresh may update only the session that requested it.
    internal fun saveFor(account: EnrollmentAccount, response: AuthResponse): Boolean = synchronized(lock) {
        if (userId != account.userId || response.user.id != account.userId) return@synchronized false
        save(response)
        true
    }

    internal fun clearFor(account: EnrollmentAccount) = synchronized(lock) {
        if (userId == account.userId) clear()
    }

    fun save(response: AuthResponse) {
        synchronized(lock) {
            // Encrypt both before changing storage, then save the pair together. The
            // synchronous getters remain available to OkHttp and the authenticator.
            val accessValue = cipher.encrypt(response.accessToken, ACCESS)
            val refreshValue = cipher.encrypt(response.refreshToken, REFRESH)
            if (!encryptedPrefs.edit().putString(ACCESS, accessValue)
                    .putString(REFRESH, refreshValue).commit()) {
                throw IOException("Unable to persist authentication tokens")
            }
            if (!prefs.edit().putString("user_id", response.user.id).commit()) {
                throw IOException("Unable to persist session owner")
            }
            sessionChanges.update { it + 1 }
        }
    }

    fun clear() {
        synchronized(lock) {
            // Remove legacy data first so an interrupted logout cannot migrate it back.
            if (!prefs.edit().clear().commit()) throw IOException("Unable to clear session metadata")
            sessionChanges.update { it + 1 }
            if (!encryptedPrefs.edit().clear().commit()) throw IOException("Unable to clear authentication tokens")
        }
    }

    private fun readToken(name: String): String? = synchronized(lock) {
        val encrypted = encryptedPrefs.getString(name, null) ?: return@synchronized null
        try {
            cipher.decrypt(encrypted, name)
        } catch (error: GeneralSecurityException) {
            rejectUnreadableSession()
            null
        } catch (error: IllegalArgumentException) {
            rejectUnreadableSession()
            null
        }
    }

    private fun rejectUnreadableSession() {
        // A changed ciphertext or missing device key must never produce a usable token.
        // Clear the session and let normal authentication ask the user to sign in again.
        // Do not log the encrypted value, plaintext token, or exception details.
        Log.w("TokenStore", "Stored authentication could not be decrypted; sign-in required")
        clear()
    }

    private fun writeToken(name: String, value: String?) {
        synchronized(lock) {
            val editor = encryptedPrefs.edit()
            if (value == null) editor.remove(name) else editor.putString(name, cipher.encrypt(value, name))
            if (!editor.commit()) throw IOException("Unable to persist authentication token")
        }
    }

    private companion object {
        // Different wrappers (including the startup migration) share one write lock.
        val lock = Any()
        // A clear through another TokenStore wrapper must also invalidate active observers.
        val sessionChanges = MutableStateFlow(0L)
        const val ACCESS = "access_token"
        const val REFRESH = "refresh_token"
    }
}
