package com.group41.kotlinapp.network

import android.content.Context
import androidx.core.content.edit

class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    var access: String?
        get() = prefs.getString("access_token", null)
        set(value) = prefs.edit { putString("access_token", value) }

    var refresh: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit { putString("refresh_token", value) }

    /** id del usuario en el backend; analytics lo necesita desde el arranque. */
    var userId: String?
        get() = prefs.getString("user_id", null)
        set(value) = prefs.edit { putString("user_id", value) }

    /** El servidor guarda el token de Google; aquí solo la bandera para la UI */
    var googleConnected: Boolean
        get() = prefs.getBoolean("google_connected", false)
        set(value) = prefs.edit { putBoolean("google_connected", value) }

    fun save(response: AuthResponse) {
        access = response.accessToken
        refresh = response.refreshToken
        userId = response.user.id
    }

    fun clear() = prefs.edit { clear() }
}
