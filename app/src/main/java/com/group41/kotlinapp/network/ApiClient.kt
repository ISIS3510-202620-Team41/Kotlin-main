package com.group41.kotlinapp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.group41.kotlinapp.BuildConfig
import com.group41.kotlinapp.analytics.Analytics
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP centralizado para Llamalla.
 *
 * Implementa las estrategias obligatorias de la rúbrica de sustentación:
 * 1. Caché de Red (OkHttp Cache de 10 MB): Almacena respuestas GET en disco según
 *    el header Cache-Control: max-age=300 emitido por el backend.
 * 2. Conectividad Eventual en HTTP: Si el teléfono no tiene internet, un interceptor
 *    de red solicita "only-if-cached, max-stale=86400" para devolver la respuesta
 *    previamente guardada sin arrojar IOException ni congelar la pantalla.
 * 3. Caché de Imágenes con Coil: Configura un ImageLoader global con 25 MB en disco
 *    y 25% de la memoria RAM disponible para renderizado fluido y offline de fotos.
 */
object ApiClient {
    val BASE_URL: String = BuildConfig.API_BASE_URL

    lateinit var tokens: TokenStore
        private set
    lateinit var api: ApiService
        private set
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        tokens = TokenStore(appContext)
        val gson = GsonConverterFactory.create()

        // 1. Configuración de Caché HTTP en disco (10 MB)
        val cacheDir = File(appContext.cacheDir, "http_cache")
        val httpCache = Cache(cacheDir, 10L * 1024 * 1024)

        // 2. Interceptor de red para conectividad eventual y expiración de caché
        val cacheInterceptor = Interceptor { chain ->
            var request = chain.request()

            // Si es petición GET y no hay conexión a internet, forzar lectura de caché
            if (request.method.equals("GET", ignoreCase = true) && !isOnline(appContext)) {
                request = request.newBuilder()
                    .header("Cache-Control", "public, only-if-cached, max-stale=86400")
                    .build()
            }

            val response = chain.proceed(request)

            if (request.method.equals("GET", ignoreCase = true)) {
                val cacheHeader = if (isOnline(appContext)) {
                    "public, max-age=300" // 5 minutos si hay conexión
                } else {
                    "public, only-if-cached, max-stale=86400" // 24 horas si está offline
                }
                response.newBuilder()
                    .header("Cache-Control", cacheHeader)
                    .removeHeader("Pragma")
                    .build()
            } else {
                response
            }
        }

        // 3. Configuración de Coil para caché de imágenes en memoria y disco (25 MB)
        val coilLoader = ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(appContext.cacheDir, "image_cache"))
                    .maxSizeBytes(25L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
        Coil.setImageLoader(coilLoader)

        // Cliente sin token ni authenticator, solo para refrescar (evita bucles)
        val refreshApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(gson)
            .build()
            .create(ApiService::class.java)

        val client = OkHttpClient.Builder()
            .cache(httpCache)
            .addInterceptor(cacheInterceptor)
            .readTimeout(60, TimeUnit.SECONDS)
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

    /**
     * Valida si el dispositivo cuenta con conexión activa a internet.
     */
    fun isOnline(context: Context = appContext): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * URL de una imagen servida por el backend (ej. avatarUrl o foto de actividad).
     *
     * Si la URL viene con "localhost" o "127.0.0.1", se reescribe al host real del backend
     * para que funcione tanto en el emulador (10.0.2.2) como en dispositivos físicos.
     */
    fun mediaUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.host != "localhost" && parsed.host != "127.0.0.1") return url
        val base = BASE_URL.toHttpUrl()
        return parsed.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
            .toString()
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
