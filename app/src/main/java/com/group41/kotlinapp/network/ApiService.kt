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

    // Synchronous on purpose: used by ApiClient automatic refresh
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
     * Response instead of direct list: headers X-Recommendation-Id and
     * X-Free-Time-Minutes must be stored alongside the list.
     */
    @GET("api/activities/recommendations")
    suspend fun recommendations(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("radius") radiusKm: Double? = null,
        @Query("tz") tz: String = TimeZone.getDefault().id
    ): Response<JsonArray>

    @GET("api/activities")
    suspend fun nearbyActivities(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("radius") radiusKm: Double
    ): Response<JsonArray>

    @GET("api/activities/{id}")
    suspend fun getActivity(
        @Path("id") activityId: String,
        @Query("lat") lat: Double? = null,
        @Query("lon") lon: Double? = null
    ): Response<JsonObject>

    @POST("api/activities")
    suspend fun createActivity(@Body body: CreateActivityRequest): Response<JsonObject>

    /**
     * recommendationId and freeTimeMinutes only if the user joins from the
     * recommendations list; from elsewhere they pass as null and Retrofit omits them.
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

/** A recommendation payload with the ID assigned by the backend. */
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

/** Join an activity retrieved from this recommendation payload. */
suspend fun ApiService.joinFromRecommendations(activityId: String, page: RecommendationsPage) =
    join(activityId, page.recommendationId, page.freeTimeMinutes)