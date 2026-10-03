package com.group41.kotlinapp.network

data class GoogleSyncRequest(val authCode: String)
data class SyncResult(val imported: Int, val skippedEvents: Int, val calendars: Int)
data class FreeSlotDto(val start: String, val end: String)
data class GapsResponse(val date: String, val timezone: String, val free: List<FreeSlotDto>)
data class LoginRequest(val email: String, val password: String)
data class RegisterRequest(val email: String, val password: String, val name: String)
data class RefreshRequest(val refreshToken: String)
data class UpdateProfileRequest(val name: String?, val bio: String?)

data class UserDto(
    val id: String,
    val email: String,
    val name: String,
    val bio: String?,
    val avatarUrl: String?
)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val user: UserDto
)

data class ApiError(val status: Int, val message: String?, val fields: Map<String, String>?)