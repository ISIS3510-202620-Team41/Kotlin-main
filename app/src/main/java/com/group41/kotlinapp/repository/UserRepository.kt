package com.group41.kotlinapp.repository

import com.group41.kotlinapp.AvatarImage
import com.group41.kotlinapp.analytics.Analytics
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.ApiService
import com.group41.kotlinapp.network.UpdateProfileRequest
import com.group41.kotlinapp.network.UserDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Perfil del usuario: datos, nombre, descripción y foto. */
interface UserRepository {
    suspend fun me(): UserDto
    suspend fun updateProfile(name: String, bio: String): UserDto

    /**
     * `prepare` produce el JPEG (desde la galería o una URL) y corre fuera del
     * hilo principal: leer, descargar y comprimir una imagen tarda.
     */
    suspend fun uploadAvatar(prepare: () -> ByteArray): UserDto
    suspend fun deleteAvatar(): UserDto
}

class ApiUserRepository(
    private val api: ApiService = ApiClient.api
) : UserRepository {

    override suspend fun me(): UserDto {
        val user = api.me()
        // Sesiones guardadas antes de que existiera userId en TokenStore
        ApiClient.tokens.userId = user.id
        Analytics.setUserId(user.id)
        return user
    }

    override suspend fun updateProfile(name: String, bio: String) =
        api.updateMe(UpdateProfileRequest(name, bio))

    override suspend fun uploadAvatar(prepare: () -> ByteArray): UserDto {
        val jpeg = withContext(Dispatchers.IO) { prepare() }
        return api.uploadAvatar(AvatarImage.toPart(jpeg))
    }

    override suspend fun deleteAvatar() = api.deleteAvatar()
}
