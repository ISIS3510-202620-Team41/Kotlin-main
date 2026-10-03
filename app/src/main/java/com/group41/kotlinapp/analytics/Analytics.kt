package com.group41.kotlinapp.analytics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import com.group41.kotlinapp.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Eventos que la app envia directo al Analytics Engine (analytics-contract.md):
 * app_loading_time, screen_view y crash.
 *
 * - Cada evento recibe eventId y timestamp al crearse; los reintentos reusan el mismo.
 * - La cola vive en disco para no perder eventos si se cierra la app.
 * - Nada se envia hasta que haya userId (setUserId tras el login).
 * - Se envia cada 30 s, al pasar a segundo plano, y con espera creciente si falla.
 */
object Analytics {

    private const val TAG = "Analytics"
    private const val PREFS = "analytics"
    private const val KEY_QUEUE = "queue"
    private const val KEY_USER_ID = "userId"
    private const val KEY_PENDING_CRASH = "pendingCrash"

    private const val BATCH_SIZE = 200
    private const val MAX_QUEUE = 2000
    private const val FLUSH_INTERVAL_MS = 30_000L
    private const val MIN_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 5 * 60_000L
    private const val MAX_DURATION_MS = 600_000L

    private lateinit var prefs: SharedPreferences
    private val queue = mutableListOf<JSONObject>()
    private val sessionId = UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()

    @Volatile
    private var userId: String? = null

    /** Pantalla visible ahora; se guarda junto al crash. */
    @Volatile
    var currentScreen: String? = null
        private set

    private var failures = 0
    private var coldStartReported = false
    private var startedActivities = 0

    fun init(app: Application) {
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        userId = prefs.getString(KEY_USER_ID, null)
        loadQueue()
        installCrashHandler()
        reportPendingCrash()
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        startFlushLoop()
    }

    fun setUserId(id: String) {
        userId = id
        prefs.edit().putString(KEY_USER_ID, id).apply()
        scope.launch { flush() }
    }

    fun clearUserId() {
        userId = null
        prefs.edit().remove(KEY_USER_ID).apply()
    }

    /** Nombres fijos compartidos con Flutter: Home, Recommendations, FriendAvailability, ActivityDetail, Profile. */
    fun screenView(screen: String) {
        currentScreen = screen
        track("screen_view", mapOf("screen" to screen))
    }

    fun track(type: String, fields: Map<String, Any?> = emptyMap(), timestamp: String = now()) {
        val event = JSONObject()
        fields.forEach { (key, value) -> if (value != null) event.put(key, value) }
        event.put("eventType", type)
        event.put("eventId", UUID.randomUUID().toString())
        event.put("sessionId", sessionId)
        event.put("appVersion", BuildConfig.VERSION_NAME)
        event.put("timestamp", timestamp)
        // Si ya hay sesion se fija ahora, asi un cambio de usuario no reasigna eventos viejos.
        userId?.let { event.put("userId", it) }

        synchronized(queue) {
            queue.add(event)
            while (queue.size > MAX_QUEUE) queue.removeAt(0)
            persistQueue()
        }
    }

    /** Envia lo pendiente y espera a que termine (por ejemplo antes de cerrar sesion). */
    suspend fun flushNow() {
        flush()
    }

    /** Devuelve true si la cola quedo al dia, false si hay que reintentar. */
    private suspend fun flush(): Boolean = flushMutex.withLock {
        withContext(Dispatchers.IO) { sendPending() }
    }

    private fun sendPending(): Boolean {
        val id = userId ?: return true
        while (true) {
            val batch = synchronized(queue) { queue.take(BATCH_SIZE).toList() }
            if (batch.isEmpty()) return true

            val payload = JSONArray()
            batch.forEach { event ->
                val copy = JSONObject(event.toString())
                if (!copy.has("userId")) copy.put("userId", id)
                payload.put(copy)
            }

            when (val code = post(JSONObject().put("events", payload))) {
                201 -> removeFromQueue(batch)
                422 -> {
                    // Reintentar no lo arregla: se descarta y se deja en el log.
                    Log.e(TAG, "El engine rechazo un lote de ${batch.size} eventos (422)")
                    removeFromQueue(batch)
                }
                401 -> {
                    Log.e(TAG, "Llave de ingesta invalida (401): revisa ANALYTICS_INGEST_KEY")
                    return false
                }
                else -> {
                    Log.w(TAG, "Fallo temporal enviando eventos (codigo $code)")
                    return false
                }
            }
        }
    }

    /** Codigo HTTP, o -1 si no hubo red. */
    private fun post(body: JSONObject): Int {
        val connection = URL(BuildConfig.ANALYTICS_URL + "/events/batch").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (BuildConfig.ANALYTICS_INGEST_KEY.isNotBlank()) {
                connection.setRequestProperty("X-API-Key", BuildConfig.ANALYTICS_INGEST_KEY)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } catch (ex: IOException) {
            -1
        } finally {
            connection.disconnect()
        }
    }

    private fun startFlushLoop() {
        scope.launch {
            while (true) {
                val ok = runCatching { flush() }.getOrDefault(false)
                val wait = if (ok) {
                    failures = 0
                    FLUSH_INTERVAL_MS
                } else {
                    failures++
                    // 2 s, 4 s, 8 s... tope 5 min.
                    (MIN_BACKOFF_MS shl (failures - 1).coerceAtMost(10)).coerceAtMost(MAX_BACKOFF_MS)
                }
                delay(wait)
            }
        }
    }

    // ------------------------------------------------------------------ cola en disco

    private fun loadQueue() {
        val saved = prefs.getString(KEY_QUEUE, null) ?: return
        runCatching {
            val array = JSONArray(saved)
            synchronized(queue) {
                for (i in 0 until array.length()) queue.add(array.getJSONObject(i))
            }
        }
    }

    private fun removeFromQueue(batch: List<JSONObject>) {
        synchronized(queue) {
            batch.forEach { sent -> queue.removeAll { it === sent } }
            persistQueue()
        }
    }

    /** Llamar con el lock de queue tomado. */
    private fun persistQueue() {
        prefs.edit().putString(KEY_QUEUE, JSONArray(queue).toString()).apply()
    }

    // ------------------------------------------------------------------ crashes

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val crash = JSONObject()
                    .put("exceptionType", error::class.java.simpleName)
                    .put("timestamp", now())
                currentScreen?.let { crash.put("screen", it) }
                // commit y no apply: el proceso muere enseguida.
                prefs.edit().putString(KEY_PENDING_CRASH, crash.toString()).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** El crash se envia en el siguiente arranque, con la hora en que ocurrio. */
    private fun reportPendingCrash() {
        val saved = prefs.getString(KEY_PENDING_CRASH, null) ?: return
        prefs.edit().remove(KEY_PENDING_CRASH).apply()
        runCatching {
            val crash = JSONObject(saved)
            track(
                "crash",
                mapOf(
                    "screen" to crash.optString("screen").ifBlank { null },
                    "exceptionType" to crash.optString("exceptionType")
                ),
                timestamp = crash.optString("timestamp").ifBlank { now() }
            )
        }
    }

    // ------------------------------------------------------------------ arranque y segundo plano

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (coldStartReported) return
            coldStartReported = true
            val content = activity.findViewById<android.view.View>(android.R.id.content)
            content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    val ms = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                    track(
                        "app_loading_time",
                        mapOf("loadType" to "cold_start", "durationMs" to ms.coerceIn(0, MAX_DURATION_MS))
                    )
                    return true
                }
            })
        }

        override fun onActivityStarted(activity: Activity) {
            startedActivities++
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities--
            if (startedActivities <= 0) {
                scope.launch { flush() }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}
