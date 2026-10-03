package com.group41.kotlinapp.network

import android.content.Context
import com.group41.kotlinapp.BuildConfig
import com.group41.kotlinapp.analytics.Analytics
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

object ApiClient {
    val BASE_URL: String = BuildConfig.API_BASE_URL

    lateinit var tokens: TokenStore
        private set
    lateinit var api: ApiService
        private set

    fun init(context: Context) {
        tokens = TokenStore(context.applicationContext)
        val gson = GsonConverterFactory.create()

        // Cliente sin token ni authenticator, solo para refrescar (evita bucles)
        val refreshApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(gson)
            .build()
            .create(ApiService::class.java)

        val client = OkHttpClient.Builder()
            // Agrega "Authorization: Bearer <token>" a todas las peticiones
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                tokens.access?.let { builder.header("Authorization", "Bearer $it") }
                chain.proceed(builder.build())
            }
            // Si el backend responde 401, refresca el token y reintenta una vez
            .authenticator { _, response -> refreshAndRetry(response, refreshApi) }
            .build()

        api = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(gson)
            .build()
            .create(ApiService::class.java)
    }

    /** Tras login o registro: guarda la sesión y habilita el envío de analytics. */
    fun onAuthenticated(response: AuthResponse) {
        tokens.save(response)
        Analytics.setUserId(response.user.id)
    }

    /**
     * Revoca el refresh token en el backend y borra la sesión local.
     * Sin red igual se cierra la sesión en el teléfono.
     */
    suspend fun logout() {
        Analytics.flushNow()
        tokens.refresh?.let { runCatching { api.logout(RefreshRequest(it)) } }
        tokens.clear()
        Analytics.clearUserId()
    }

    @Synchronized
    private fun refreshAndRetry(response: Response, refreshApi: ApiService): Request? {
        if (response.request.url.encodedPath.startsWith("/api/auth/")) return null
        if (response.priorResponse != null) return null

        // Si otra petición ya refrescó mientras esperábamos, usar ese token
        val sent = response.request.header("Authorization")?.removePrefix("Bearer ")
        val current = tokens.access
        if (current != null && current != sent) {
            return response.request.newBuilder().header("Authorization", "Bearer $current").build()
        }

        val refreshToken = tokens.refresh ?: return null
        val result = try {
            refreshApi.refresh(RefreshRequest(refreshToken)).execute()
        } catch (e: IOException) {
            return null
        }
        val body = result.body()
        if (result.code() == 401 || result.code() == 400) {
            tokens.clear() // el refresh también venció: hay que volver al login
            return null
        }
        // Un 5xx no significa que la sesión murió: no se cierra, se reintenta luego.
        if (!result.isSuccessful || body == null) return null
        tokens.save(body)
        return response.request.newBuilder()
            .header("Authorization", "Bearer ${body.accessToken}")
            .build()
    }
}