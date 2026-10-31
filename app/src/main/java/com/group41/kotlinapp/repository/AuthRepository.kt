package com.group41.kotlinapp.repository

import com.group41.kotlinapp.AppPreferences
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.ApiService
import com.group41.kotlinapp.network.LoginRequest
import com.group41.kotlinapp.network.RegisterRequest
import com.group41.kotlinapp.network.UserDto

/**
 * Sesión del usuario: login, registro y logout.
 *
 * Es una interfaz para que los ViewModel se puedan probar con una versión
 * falsa, sin backend (ver app/src/test).
 */
interface AuthRepository {
    fun hasSession(): Boolean
    suspend fun login(email: String, password: String): UserDto
    suspend fun register(name: String, email: String, password: String): UserDto
    suspend fun logout()

    /** La sesión venció y el refresh ya no sirve: se borra sin llamar al backend */
    suspend fun clearSession()
}

class ApiAuthRepository(
    private val api: ApiService = ApiClient.api,
    private val preferences: AppPreferences = AppPreferences(ApiClient.appContext)
) : AuthRepository {

    override fun hasSession() = ApiClient.tokens.refresh != null

    override suspend fun login(email: String, password: String): UserDto {
        val response = api.login(LoginRequest(email, password))
        // Guarda los tokens y activa analytics con el userId
        ApiClient.onAuthenticated(response)
        // Keep the UUID in DataStore as well, so screens can observe the current account.
        // TokenStore still provides the synchronous token access used by authentication.
        preferences.saveUserId(response.user.id)
        return response.user
    }

    override suspend fun register(name: String, email: String, password: String): UserDto {
        val response = api.register(RegisterRequest(email, password, name))
        // El registro ya deja la sesión iniciada
        ApiClient.onAuthenticated(response)
        // Registration signs the user in, so save their UUID just as we do after login.
        preferences.saveUserId(response.user.id)
        return response.user
    }

    override suspend fun logout() {
        ApiClient.logout()
        // Clear account metadata only after the existing logout has cleared the session.
        // This also removes the sync timestamp, which belongs to the signed-out account.
        preferences.clear()
    }

    override suspend fun clearSession() {
        // Expiration ends the local session without making a logout request to the server.
        // Wait for DataStore too, so callers only show the expired-session screen after
        // the old account's UUID and sync timestamp have been removed.
        ApiClient.tokens.clear()
        preferences.clear()
    }
}
