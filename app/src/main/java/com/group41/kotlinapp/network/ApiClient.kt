package com.group41.kotlinapp.network

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

object ApiClient {
    const val BASE_URL = "http://10.0.2.2:8080/"

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
        if (!result.isSuccessful || body == null) {
            tokens.clear() // el refresh también venció: hay que volver al login
            return null
        }
        tokens.save(body)
        return response.request.newBuilder()
            .header("Authorization", "Bearer ${body.accessToken}")
            .build()
    }
}