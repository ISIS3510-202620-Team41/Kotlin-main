package com.group41.kotlinapp.repository

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
    fun clearSession()
}

class ApiAuthRepository(
    private val api: ApiService = ApiClient.api
) : AuthRepository {

    override fun hasSession() = ApiClient.tokens.refresh != null

    override suspend fun login(email: String, password: String): UserDto {
        val response = api.login(LoginRequest(email, password))
        // Guarda los tokens y activa analytics con el userId
        ApiClient.onAuthenticated(response)
        return response.user
    }

    override suspend fun register(name: String, email: String, password: String): UserDto {
        val response = api.register(RegisterRequest(email, password, name))
        // El registro ya deja la sesión iniciada
        ApiClient.onAuthenticated(response)
        return response.user
    }

    override suspend fun logout() = ApiClient.logout()

    override fun clearSession() = ApiClient.tokens.clear()
}
