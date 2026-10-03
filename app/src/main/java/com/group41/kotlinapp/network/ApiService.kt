package com.group41.kotlinapp.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.TimeZone

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

    /**
     * Response y no la lista directa: los headers X-Recommendation-Id y
     * X-Free-Time-Minutes hay que guardarlos junto a la lista (ver RecommendationsPage).
     */
    @GET("api/activities/recommendations")
    suspend fun recommendations(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("radius") radiusKm: Double? = null,
        @Query("tz") tz: String = TimeZone.getDefault().id
    ): Response<JsonArray>

    /**
     * recommendationId y freeTimeMinutes solo si el usuario se une desde la
     * lista de recomendaciones; desde otro lugar van null y Retrofit los omite.
     */
    @POST("api/activities/{id}/join")
    suspend fun join(
        @Path("id") activityId: String,
        @Query("recommendationId") recommendationId: String? = null,
        @Query("freeTimeMinutes") freeTimeMinutes: Int? = null
    ): JsonObject

    @POST("api/schedules/sync/google")
    suspend fun syncGoogle(
        @Body body: GoogleSyncRequest,
        @Query("tz") tz: String = TimeZone.getDefault().id
    ): SyncResult

    @POST("api/schedules/sync/google/refresh")
    suspend fun refreshGoogle(@Query("tz") tz: String = TimeZone.getDefault().id): SyncResult

    @GET("api/schedules/me/gaps")
    suspend fun myGaps(
        @Query("date") date: String,
        @Query("tz") tz: String = TimeZone.getDefault().id
    ): GapsResponse
}

/** Una carga de recomendaciones con el id que el backend le asignó. */
class RecommendationsPage(
    val items: JsonArray,
    val recommendationId: String?,
    val freeTimeMinutes: Int?
) {
    companion object {
        fun from(response: Response<JsonArray>) = RecommendationsPage(
            items = response.body() ?: JsonArray(),
            recommendationId = response.headers()["X-Recommendation-Id"],
            freeTimeMinutes = response.headers()["X-Free-Time-Minutes"]?.toIntOrNull()
        )
    }
}

/** Unirse a una actividad que vino de esta carga de recomendaciones. */
suspend fun ApiService.joinFromRecommendations(activityId: String, page: RecommendationsPage) =
    join(activityId, page.recommendationId, page.freeTimeMinutes)