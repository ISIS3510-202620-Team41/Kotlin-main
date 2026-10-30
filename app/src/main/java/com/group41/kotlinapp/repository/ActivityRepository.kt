package com.group41.kotlinapp.repository

import com.group41.kotlinapp.cache.ActivityMemoryCache
import com.group41.kotlinapp.data.local.dao.ActivityDao
import com.group41.kotlinapp.data.local.toActivity
import com.group41.kotlinapp.data.local.toEntity
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.model.JoinPayload
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.RecommendationsPage
import com.group41.kotlinapp.sync.SyncScheduler
import retrofit2.HttpException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Repositorio de Actividades con soporte para Conectividad Eventual, Caché en Memoria y Persistencia en Room.
 *
 * Aplica los principios de la rúbrica de sustentación:
 * 1. Estrategia Cache-First & Persistencia Local: Guarda respuestas de la red en la caché LRU (memoria)
 *    y en el DAO de Room (disco). Si el proceso se reinicia o no hay red, lee desde la base de datos local.
 * 2. Cálculo Local de Distancia (Haversine): Cuando el dispositivo está sin conexión,
 *    calcula la distancia exacta en metros entre el GPS del usuario y las coordenadas guardadas.
 * 3. Ejecución Multi-hilo: Delegado a Dispatchers.IO para mantener la UI fluida.
 *
 * @param activityDao Data Access Object for Room database interactions.
 */
/**
 * Como termino una inscripcion, desde el punto de vista del usuario.
 *
 * Existe para que la pantalla pueda decir la verdad. Antes `joinActivity` no devolvia nada:
 * o funcionaba, o lanzaba un error. Con la cola offline aparecio un tercer final -- "quedo
 * guardada pero el servidor todavia no la sabe" -- y sin este dato la pantalla mostraria un
 * mensaje de exito que no seria cierto.
 */
enum class JoinResult {
    /** El backend confirmo la inscripcion. */
    SYNCED,

    /** No se pudo mandar, quedo guardada en el telefono y se enviara sola mas tarde. */
    QUEUED
}

class ActivityRepository(
    private val activityDao: ActivityDao? = null,

    /**
     * El buzon donde se guardan las acciones que no se pudieron mandar.
     *
     * Es opcional (puede ser null) por dos razones. La primera es que los tests de calculo
     * de distancia construyen el repositorio sin nada y no deben verse obligados a montar
     * una base de datos entera. La segunda es que, si por lo que sea no hay buzon, lo
     * correcto es que la inscripcion falle a la vista del usuario y no que se pierda
     * en silencio creyendo el que quedo guardada.
     */
    private val pendingActions: PendingActionRepository? = null,
    private val apiProvider: () -> com.group41.kotlinapp.network.ApiService = { ApiClient.api },
    private val online: () -> Boolean = { ApiClient.isOnline() }
) {

    /**
     * Inscribe al usuario en una actividad, con o sin internet.
     *
     * Este es el comportamiento que pide el issue para la Vista 4: el usuario toca "Unirme"
     * sin conexion, la pantalla responde de inmediato como si hubiera funcionado, y la
     * peticion real sale sola cuando vuelva el internet.
     *
     * Las tres rutas posibles:
     *
     * 1. Hay internet y el backend acepta -> SYNCED. Es el camino normal de siempre.
     * 2. No hay internet, o la hay pero el envio falla -> se guarda en la cola y se
     *    devuelve QUEUED. Para el usuario la inscripcion ya esta hecha.
     * 3. El backend responde que NO (la actividad se lleno, ya estabas inscrito) -> se
     *    lanza el error para que la pantalla lo muestre. Aqui no se encola nada: guardar
     *    algo que el servidor ya rechazo solo serviria para volver a ser rechazado.
     */
    suspend fun joinActivity(
        activityId: String,
        recommendationId: String?,
        freeTimeMinutes: Int?
    ): JoinResult = withContext(Dispatchers.IO) {

        val payload = JoinPayload(activityId, recommendationId, freeTimeMinutes)

        // Si ya se sabe que no hay conexion, ni se intenta la peticion. No es solo ahorrar
        // tiempo: intentarla significaria que el usuario ve el boton girando varios segundos
        // hasta que se agote la espera, cuando se le puede responder al instante.
        if (!online()) {
            return@withContext queueJoin(payload)
        }

        try {
            apiProvider().join(activityId, recommendationId, freeTimeMinutes)
            markJoinedLocally(activityId, pendingSync = false)
            getActivityById(activityId)
            ActivityChanges.notifyChanged()
            JoinResult.SYNCED

        } catch (e: IOException) {
            // Habia internet al empezar pero la peticion no llego a completarse: se cayo la
            // senal a mitad de camino, o el backend no respondio. El usuario no tiene la
            // culpa ni puede hacer nada, asi que se guarda en vez de mostrarle un error.
            queueJoin(payload)

        } catch (e: HttpException) {
            // El servidor contesto, pero con un error. El numero decide que hacer:
            // 5xx es un problema del servidor que suele pasarse solo, asi que se guarda.
            // 4xx significa que esta inscripcion concreta no se puede hacer, y eso el
            // usuario si necesita saberlo.
            if (e.code() == 409) {
                // A conflict can also mean full/closed. Only accept confirmed membership.
                val refreshed = getActivityById(activityId, serverOnly = true)
                if (refreshed?.joined == true && !refreshed.pendingSync) {
                    ActivityChanges.notifyChanged()
                    JoinResult.SYNCED
                } else throw e
            } else if (e.code() >= 500) queueJoin(payload) else throw e
        }
    }

    /**
     * Guarda la inscripcion para mandarla despues y deja la pantalla lista.
     *
     * Hace tres cosas, en este orden:
     * 1. La mete en el buzon, para que no se pierda aunque el usuario cierre la app.
     * 2. Marca la actividad como inscrita y pendiente, para que la pantalla lo muestre.
     * 3. Le pide a Android que la mande cuando haya internet.
     */
    private suspend fun queueJoin(payload: JoinPayload): JoinResult {
        // Sin buzon o sin sesion no hay donde, ni a nombre de quien, guardar. Se lanza el
        // error para que el usuario vea que no funciono, en vez de decirle que quedo
        // guardada cuando en realidad se perdio.
        val queue = pendingActions
            ?: throw IOException("No se pudo conectar con el servidor")
        val userId = ApiClient.tokens.userId
            ?: throw IOException("No se pudo conectar con el servidor")

        queue.enqueueJoin(userId, payload)

        // Esto es lo que hace que la app se sienta instantanea sin conexion: la pantalla lee
        // de la copia local, asi que al marcarlo aqui el cambio se ve de inmediato, mucho
        // antes de que el backend se entere. Se le llama actualizacion optimista, porque se
        // asume que va a funcionar. El aviso de "pendiente" es lo que mantiene la honestidad,
        // porque deja claro que todavia no esta confirmado.
        markJoinedLocally(payload.activityId, pendingSync = true)

        SyncScheduler.schedule(ApiClient.appContext)
        ActivityChanges.notifyChanged()
        return JoinResult.QUEUED
    }

    /**
     * Marca la actividad como inscrita en las dos capas locales: memoria y disco.
     *
     * Las dos, y no solo una, porque cumplen funciones distintas. La memoria es lo que la
     * pantalla ya tiene cargado y permite que el cambio se vea al instante. El disco es lo
     * que sobrevive a cerrar la app, y sin el, al volver a abrirla la inscripcion pendiente
     * habria desaparecido.
     *
     * @param pendingSync true si el backend todavia no lo sabe.
     */
    private suspend fun markJoinedLocally(activityId: String, pendingSync: Boolean) {
        // Se busca primero en memoria porque es lo mas rapido y lo mas probable: el usuario
        // acaba de tocar el boton de una actividad que esta viendo, asi que ya esta cargada.
        val current = ActivityMemoryCache.get(activityId)
            ?: activityDao?.getActivityById(activityId)?.toActivity()
            ?: return   // No esta en ningun lado: no hay nada que marcar.

        val updated = current.copy(
            joined = true, pendingSync = pendingSync,
            participants = current.participants + if (current.joined || current.pendingSync) 0 else 1
        )
        ActivityMemoryCache.put(updated)
        activityDao?.insertActivity(updated.toEntity())
    }

    /**
     * Consulta recomendaciones aplicando estrategia Cache-First con respaldo en Room.
     */
    suspend fun getRecommendedActivities(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 10.0
    ): List<Activity> = withContext(Dispatchers.IO) {
        try {
            if (!online()) throw IOException("Offline")
            val response = apiProvider().recommendations(
                lat = latitude,
                lon = longitude,
                radiusKm = radiusKm
            )

            if (!response.isSuccessful) {
                val cached = getCachedWithUpdatedDistances(latitude, longitude, radiusKm)
                if (cached.isNotEmpty()) return@withContext cached
                throw Exception("Backend returned HTTP ${response.code()}")
            }

            val page = RecommendationsPage.from(response)
            val activities = parseRecommendations(
                json = page.items.toString(),
                recommendationId = page.recommendationId,
                freeTimeMinutes = page.freeTimeMinutes,
                userLat = latitude,
                userLon = longitude
            )

            ActivityMemoryCache.putAll(activities)
            activityDao?.insertActivities(activities.map { it.toEntity() })

            activities
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cached = getCachedWithUpdatedDistances(latitude, longitude, radiusKm)
            if (cached.isNotEmpty()) {
                return@withContext cached
            }
            throw e
        }
    }

    /**
     * Obtiene actividades cercanas persistiéndolas localmente para uso offline.
     */
    suspend fun getNearbyActivities(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 1.0
    ): List<Activity> = withContext(Dispatchers.IO) {
        try {
            if (!online()) throw IOException("Offline")
            val response = apiProvider().nearbyActivities(
                lat = latitude,
                lon = longitude,
                radiusKm = radiusKm
            )

            if (!response.isSuccessful) {
                val cached = getCachedWithUpdatedDistances(latitude, longitude, radiusKm)
                if (cached.isNotEmpty()) return@withContext cached
                throw Exception("Backend returned HTTP ${response.code()}")
            }

            val json = response.body()?.toString() ?: "[]"
            val activities = parseNearbyActivities(json, userLat = latitude, userLon = longitude)

            ActivityMemoryCache.putAll(activities)
            activityDao?.insertActivities(activities.map { it.toEntity() })

            activities
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cached = getCachedWithUpdatedDistances(latitude, longitude, radiusKm)
            if (cached.isNotEmpty()) return@withContext cached
            throw e
        }
    }

    /**
     * Obtiene el detalle de una actividad específica (usado por la vista protegida de Detalle).
     */
    suspend fun getActivityById(
        activityId: String,
        userLat: Double? = null,
        userLon: Double? = null,
        serverOnly: Boolean = false
    ): Activity? = withContext(Dispatchers.IO) {
        if (!online()) {
            return@withContext if (serverOnly) null else
                (activityDao?.getActivityById(activityId)?.toActivity() ?: ActivityMemoryCache.get(activityId))
                    ?.let { formatDistance(it, userLat, userLon) }
        }
        try {
            val response = apiProvider().getActivity(activityId, userLat, userLon)
            if (response.isSuccessful && response.body() != null) {
                val jsonObject = JSONObject(response.body().toString())
                val parsed = parseSingleActivity(jsonObject, userLat = userLat, userLon = userLon)
                val activity = if (serverOnly) parsed else mergeLocalMembership(parsed)

                if (serverOnly && !activity.joined) return@withContext activity
                ActivityMemoryCache.put(activity)
                activityDao?.insertActivity(activity.toEntity())

                return@withContext activity
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Ignore network errors to proceed to local fallback
        }

        if (serverOnly) return@withContext null
        activityDao?.getActivityById(activityId)?.toActivity()?.let { storedActivity ->
            ActivityMemoryCache.put(storedActivity)
            return@withContext formatDistance(storedActivity, userLat, userLon)
        }

        ActivityMemoryCache.get(activityId)?.let { formatDistance(it, userLat, userLon) }
    }

    /**
     * Resultado de una busqueda por cercania hecha solo con lo que hay guardado en el telefono.
     *
     * @param activities las actividades dentro del radio, de la mas cercana a la mas lejana.
     * @param elapsedMillis cuanto tardo el calculo. Se mide y se devuelve para poder
     *        demostrar que el trabajo pesado corre fuera del hilo de la pantalla y que, aun
     *        asi, termina en milisegundos.
     */
    data class LocalNearbyResult(
        val activities: List<Activity>,
        val elapsedMillis: Long
    )

    /**
     * Busca actividades cerca del usuario sin tocar la red: todo sale de la base local.
     *
     * Este es el comportamiento offline que pide la Vista 3. El telefono ya tiene guardadas
     * las actividades que descargo antes, con sus coordenadas. Teniendo la posicion del
     * usuario, se puede calcular aqui mismo cuales quedan dentro del radio, sin preguntarle
     * nada al servidor.
     *
     * LO IMPORTANTE DE ESTA FUNCION: EN QUE HILO CORRE CADA COSA
     *
     * Hay dos trabajos distintos y cada uno va a un sitio distinto:
     *
     * 1. Leer la base de datos -> Dispatchers.IO. Ese grupo de hilos esta pensado para
     *    tareas que se pasan el rato ESPERANDO (al disco, a la red). Como esperan sin hacer
     *    nada, pueden ser muchos a la vez sin estorbarse.
     *
     * 2. Calcular las distancias -> Dispatchers.Default. Ese grupo tiene tantos hilos como
     *    nucleos tiene el procesador, porque aqui no se espera a nadie: se hace cuenta tras
     *    cuenta, y poner mas hilos que nucleos solo haria que se peleen por el procesador.
     *
     * Usar IO para las dos cosas tambien "funcionaria", pero seria usar la herramienta
     * equivocada. Y hacerlo en el hilo principal congelaria la pantalla: con mil actividades
     * guardadas, el usuario veria la app trabada mientras se calculan mil raices cuadradas.
     */
    suspend fun nearbyFromLocalDatabase(
        userLat: Double,
        userLon: Double,
        radiusKm: Double
    ): LocalNearbyResult {
        // Paso 1: traer lo guardado. En el hilo de espera, porque esto es leer disco.
        //
        // Se lee del DISCO primero, no de la memoria, y el motivo es importante:
        // ActivityMemoryCache es una cache LRU con tope de 50 actividades. Esta pensada para
        // buscar UNA por su id, donde responde al instante, no para recorrerlas todas.
        //
        // Si aqui se leyera de la memoria, un usuario con 200 actividades guardadas solo
        // veria las ultimas 50 que miro, y las otras 150 desapareceran de la busqueda sin
        // que nada lo avise. Una actividad a dos cuadras no apareceria solo porque hace
        // rato que no la abre.
        //
        // La memoria queda como respaldo para cuando no hay base de datos a mano.
        val stored = withContext(Dispatchers.IO) {
            val fromDisk = activityDao?.getAllActivities()?.first()?.map { it.toActivity() }
            if (!fromDisk.isNullOrEmpty()) fromDisk else ActivityMemoryCache.snapshot()
        }

        // Paso 2: filtrar y medir. En el hilo de calculo, porque esto es pura aritmetica.
        return withContext(Dispatchers.Default) {
            val startedAt = System.nanoTime()

            val withinRadius = stored
                // Las que no traen coordenadas no se pueden ubicar en el mapa, asi que no
                // se pueden comparar contra un radio. Se dejan fuera en vez de colarlas con
                // una distancia inventada de 0, que las pondria siempre de primeras.
                .filter { it.latitude != 0.0 || it.longitude != 0.0 }
                .map { activity ->
                    val meters = calculateHaversineDistanceMeters(
                        userLat, userLon, activity.latitude, activity.longitude
                    )
                    // Se guarda la distancia recalculada: la que venia del servidor se midio
                    // desde donde estaba el usuario ANTES, y pudo haberse movido desde
                    // entonces. Esta es la real respecto a donde esta ahora.
                    activity.copy(distanceMeters = meters)
                }
                .filter { it.distanceMeters <= radiusKm * 1000 }
                // De la mas cercana a la mas lejana: es el orden en el que el usuario quiere
                // verlas, y ordenar aqui evita que la pantalla tenga que hacerlo despues.
                .sortedBy { it.distanceMeters }

            // nanoTime y no currentTimeMillis: el primero mide tiempo transcurrido de verdad
            // y no se ve afectado si el reloj del telefono se ajusta a mitad de la medicion.
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000

            LocalNearbyResult(withinRadius, elapsed)
        }
    }

    /**
     * Retorna las actividades en caché recalculando las distancias localmente y filtrando por radio.
     * Si la memoria está vacía, rehidrata los datos guardados en Room.
     */
    private suspend fun getCachedWithUpdatedDistances(
        userLat: Double,
        userLon: Double,
        radiusKm: Double? = null
    ): List<Activity> {
        // Room contains the full persisted set; the bounded LRU may contain only a subset.
        val stored = activityDao?.getAllActivities()?.first()?.map { it.toActivity() }
        val cached = if (!stored.isNullOrEmpty()) stored else ActivityMemoryCache.snapshot()
        ActivityMemoryCache.putAll(cached)

        if (cached.isEmpty()) return emptyList()

        return cached.mapNotNull { act ->
            if (act.latitude != 0.0 && act.longitude != 0.0) {
                val newDistanceMeters = calculateHaversineDistanceMeters(userLat, userLon, act.latitude, act.longitude)
                val distanceKm = newDistanceMeters / 1000.0

                if (radiusKm == null || distanceKm <= radiusKm) {
                    act.copy(distanceMeters = newDistanceMeters)
                } else {
                    null
                }
            } else {
                if (radiusKm == null) act else null
            }
        }
    }

    private suspend fun mergeLocalMembership(remote: Activity): Activity {
        val local = activityDao?.getActivityById(remote.id)?.toActivity() ?: ActivityMemoryCache.get(remote.id)
        return if (local?.pendingSync == true && !remote.joined) {
            remote.copy(joined = true, pendingSync = true, participants = remote.participants + 1)
        } else remote
    }

    private fun formatDistance(activity: Activity, userLat: Double?, userLon: Double?): Activity {
        return if (userLat != null && userLon != null && activity.latitude != 0.0) {
            val distMeters = calculateHaversineDistanceMeters(userLat, userLon, activity.latitude, activity.longitude)
            activity.copy(distanceMeters = distMeters)
        } else {
            activity
        }
    }

    fun calculateHaversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        if (lat1 == 0.0 && lon1 == 0.0 || lat2 == 0.0 && lon2 == 0.0) return 0
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val distanceKm = earthRadiusKm * c
        return (distanceKm * 1000).toInt()
    }

    private suspend fun parseRecommendations(
        json: String,
        recommendationId: String?,
        freeTimeMinutes: Int?,
        userLat: Double,
        userLon: Double
    ): List<Activity> {
        val array = JSONArray(json)
        val activities = mutableListOf<Activity>()

        for (i in 0 until array.length()) {
            val recommendation = array.getJSONObject(i)
            val activityJson = recommendation.getJSONObject("activity")
            val score = recommendation.optDouble("score", 0.0)

            val activity = parseSingleActivity(
                activityJson = activityJson,
                recommendationId = recommendationId,
                freeTimeMinutes = freeTimeMinutes,
                score = score,
                userLat = userLat,
                userLon = userLon
            )
            activities.add(mergeLocalMembership(activity))
        }

        return activities
    }

    private suspend fun parseNearbyActivities(json: String, userLat: Double, userLon: Double): List<Activity> {
        val array = JSONArray(json)
        val activities = mutableListOf<Activity>()

        for (i in 0 until array.length()) {
            val activityJson = array.getJSONObject(i)
            activities.add(mergeLocalMembership(parseSingleActivity(activityJson, userLat = userLat, userLon = userLon)))
        }

        return activities
    }

    private fun parseSingleActivity(
        activityJson: JSONObject,
        recommendationId: String? = null,
        freeTimeMinutes: Int? = null,
        score: Double = 0.0,
        userLat: Double? = null,
        userLon: Double? = null
    ): Activity {
        val startTimeStr = activityJson.optString("startTime", "")
        val endTimeStr = activityJson.optString("endTime", "")

        val durationMinutes = if (activityJson.has("durationMinutes") && !activityJson.isNull("durationMinutes")) {
            activityJson.getInt("durationMinutes")
        } else if (startTimeStr.isNotBlank() && endTimeStr.isNotBlank()) {
            try {
                val start = OffsetDateTime.parse(startTimeStr)
                val end = OffsetDateTime.parse(endTimeStr)
                ChronoUnit.MINUTES.between(start, end).toInt()
            } catch (_: Exception) {
                60
            }
        } else {
            60
        }

        val actLat = activityJson.optDouble("latitude", 0.0)
        val actLon = activityJson.optDouble("longitude", 0.0)

        val distanceMeters = if (!activityJson.isNull("distanceKm")) {
            (activityJson.getDouble("distanceKm") * 1000).toInt()
        } else if (userLat != null && userLon != null && actLat != 0.0 && actLon != 0.0) {
            calculateHaversineDistanceMeters(userLat, userLon, actLat, actLon)
        } else {
            0
        }

        val rawImage = if (activityJson.has("imageUrl") && !activityJson.isNull("imageUrl")) activityJson.getString("imageUrl") else null
        val imageUrl = ApiClient.mediaUrl(rawImage)

        val price = if (activityJson.isNull("price") || activityJson.optString("price", "").isBlank()) {
            "Gratis"
        } else {
            activityJson.getString("price")
        }

        val capacity = if (activityJson.isNull("capacity")) null else activityJson.optInt("capacity")
        val participants = activityJson.optInt("participants", 0)
        val aboutLocation = if (activityJson.has("aboutLocation") && !activityJson.isNull("aboutLocation")) activityJson.getString("aboutLocation") else null
        val description = if (activityJson.has("description") && !activityJson.isNull("description")) activityJson.getString("description") else null
        val locationName = activityJson.optString("locationName", "Punto de encuentro")

        return Activity(
            id = activityJson.getString("id"),
            name = activityJson.getString("title"),
            category = formatCategory(activityJson.getString("category")),
            durationMinutes = durationMinutes,
            distanceMeters = distanceMeters,
            score = score,
            joined = activityJson.optBoolean("joined", false),
            recommendationId = recommendationId,
            freeTimeMinutes = freeTimeMinutes,
            price = price,
            capacity = capacity,
            participants = participants,
            aboutLocation = aboutLocation,
            locationName = locationName,
            latitude = actLat,
            longitude = actLon,
            imageUrl = imageUrl,
            description = description,
            startTime = startTimeStr,
            endTime = endTimeStr
        )
    }

    private fun formatCategory(category: String): String {
        return when (category.uppercase()) {
            "DEPORTES" -> "Deportes"
            "ESTUDIO" -> "Estudio"
            "CULTURA" -> "Cultura"
            "ENTRETENIMIENTO" -> "Entretenimiento"
            else -> category
        }
    }
}