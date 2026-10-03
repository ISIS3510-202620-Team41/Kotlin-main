package com.group41.kotlinapp.network

import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    // Síncrono a propósito: lo usa el refresh automático de ApiClient
    @POST("api/auth/refresh")
    fun refresh(@Body body: RefreshRequest): Call<AuthResponse>

    @POST("api/auth/logout")
    suspend fun logout(@Body body: RefreshRequest): Response<Unit>

    @GET("api/users/me")
    suspend fun me(): UserDto

    @PATCH("api/users/me")
    suspend fun updateMe(@Body body: UpdateProfileRequest): UserDto

    @Multipart
    @POST("api/users/me/avatar")
    suspend fun uploadAvatar(@Part file: MultipartBody.Part): UserDto

    @DELETE("api/users/me/avatar")
    suspend fun deleteAvatar(): UserDto
}