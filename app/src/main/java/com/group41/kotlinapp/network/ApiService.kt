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
import retrofit2.http.Tag
import java.util.TimeZone

/** Identifies the account that must authenticate an enrollment request. */
data class EnrollmentAccount(val userId: String)

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
        @Query("tz") tz: String = TimeZone.getDefault().id,
        @Tag account: EnrollmentAccount? = null
    ): Response<JsonArray>

    @GET("api/activities")
    suspend fun nearbyActivities(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("radius") radiusKm: Double,
        @Tag account: EnrollmentAccount? = null
    ): Response<JsonArray>

    @GET("api/activities/{id}")
    suspend fun getActivity(
        @Path("id") activityId: String,
        @Query("lat") lat: Double? = null,
        @Query("lon") lon: Double? = null,
        @Tag account: EnrollmentAccount? = null
    ): Response<JsonObject>

    @POST("api/activities")
    suspend fun createActivity(@Body body: CreateActivityRequest): Response<JsonObject>

    /** Upload the JPEG only after creation has supplied the backend activity ID. */
    @Multipart
    @POST("api/activities/{id}/image")
    suspend fun uploadActivityImage(
        @Path("id") activityId: String,
        @Part file: MultipartBody.Part
    ): Response<JsonObject>

    /**
     * recommendationId and freeTimeMinutes only if the user joins from the
     * recommendations list; from elsewhere they pass as null and Retrofit omits them.
     */
    @POST("api/activities/{id}/join")
    suspend fun join(
        @Path("id") activityId: String,
        @Query("recommendationId") recommendationId: String? = null,
        @Query("freeTimeMinutes") freeTimeMinutes: Int? = null,
        @Tag account: EnrollmentAccount? = null
    ): JsonObject

    /** Cancels the authenticated user's enrollment in the specified activity. */
    @DELETE("api/activities/{id}/leave")
    suspend fun leave(
        @Path("id") activityId: String,
        @Tag account: EnrollmentAccount? = null
    ): Response<Unit>

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

    @GET("api/friends")
    suspend fun listFriends(): List<UserDto>

    @GET("api/friends/schedules")
    suspend fun friendSchedules(
        @Query("date") date: String,
        @Query("tz") tz: String = TimeZone.getDefault().id
    ): List<FriendScheduleDto>

    // ----- Cupones comerciales (CampusDeals) -----

    /**
     * El catalogo vigente.
     *
     * LA MITAD HTTP DEL CACHE DE CUPONES
     *
     * Quien decide que esta respuesta se puede guardar es el SERVIDOR, no el telefono:
     * `DealController` la devuelve con `Cache-Control: public, max-age=300`. OkHttp lee
     * ese header y guarda el cuerpo en su cache de disco de 10 MB.
     *
     * Que el cliente ponga el mismo header en la respuesta no serviria para esto, y
     * conviene saberlo: el `Cache` de OkHttp vive mas adentro de la cadena que los
     * interceptores de aplicacion, asi que lo que se guarda es la respuesta tal como
     * llego de la red, antes de que un interceptor de aplicacion la reescriba. Lo que SI
     * funciona desde esa capa es reescribir la PETICION, que es como se pide la copia
     * guardada cuando no hay conexion (`only-if-cached`).
     *
     * A diferencia de `/api/activities`, este catalogo no se fuerza a `no-cache` estando
     * en linea, asi que los cinco minutos se aprovechan de verdad. Ver la excepcion
     * documentada en `ApiClient`.
     *
     * Son dos caches encima del otro y cada uno cubre algo distinto: este, de disco,
     * ahorra la peticion de red; `DealsMemoryCache` ahorra incluso la lectura de disco.
     */
    @GET("api/deals")
    suspend fun deals(): List<DealDto>

    /** Reporta en lote que unos cupones se mostraron o se abrieron. */
    @POST("api/deals/interactions")
    suspend fun reportDealInteractions(@Body body: DealInteractionBatch): Int

    /**
     * Redime un cupon.
     *
     * El contexto del descanso viaja en el cuerpo porque solo el telefono sabe si el
     * estudiante estaba en un hueco en el momento de redimir. Para una redencion hecha
     * sin conexion y enviada horas despues, calcularlo en el servidor con la hora de
     * llegada registraria el descanso equivocado.
     */
    @POST("api/deals/{id}/redeem")
    suspend fun redeemDeal(
        @Path("id") dealId: String,
        @Body context: DealInteractionDto
    ): RedemptionDto
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
