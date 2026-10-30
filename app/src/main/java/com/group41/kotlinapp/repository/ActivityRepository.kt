package com.group41.kotlinapp.repository

import com.group41.kotlinapp.cache.ActivityMemoryCache
import com.group41.kotlinapp.data.local.dao.ActivityDao
import com.group41.kotlinapp.data.local.toActivity
import com.group41.kotlinapp.data.local.toEntity
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.RecommendationsPage
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
class ActivityRepository(
    private val activityDao: ActivityDao? = null
) {

    /**
     * Une al usuario a una actividad en el backend y actualiza la caché local (memoria y disco).
     */
    suspend fun joinActivity(
        activityId: String,
        recommendationId: String?,
        freeTimeMinutes: Int?
    ) = withContext(Dispatchers.IO) {
        ApiClient.api.join(
            activityId,
            recommendationId,
            freeTimeMinutes
        )

        val memoryActivity = ActivityMemoryCache.get(activityId)
        if (memoryActivity != null) {
            val updated = memoryActivity.copy(joined = true)
            ActivityMemoryCache.put(updated)
            activityDao?.insertActivity(updated.toEntity())
        } else {
            val storedEntity = activityDao?.getActivityById(activityId)
            if (storedEntity != null) {
                val updated = storedEntity.toActivity().copy(joined = true)
                ActivityMemoryCache.put(updated)
                activityDao?.insertActivity(updated.toEntity())
            }
        }
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
            val response = ApiClient.api.recommendations(
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
            val response = ApiClient.api.nearbyActivities(
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
        userLon: Double? = null
    ): Activity? = withContext(Dispatchers.IO) {
        ActivityMemoryCache.get(activityId)?.let { cached ->
            return@withContext formatDistance(cached, userLat, userLon)
        }

        try {
            val response = ApiClient.api.getActivity(activityId, userLat, userLon)
            if (response.isSuccessful && response.body() != null) {
                val jsonObject = JSONObject(response.body().toString())
                val activity = parseSingleActivity(jsonObject, userLat = userLat, userLon = userLon)

                ActivityMemoryCache.put(activity)
                activityDao?.insertActivity(activity.toEntity())

                return@withContext activity
            }
        } catch (_: Exception) {
            // Ignore network errors to proceed to local fallback
        }

        activityDao?.getActivityById(activityId)?.toActivity()?.let { storedActivity ->
            ActivityMemoryCache.put(storedActivity)
            return@withContext formatDistance(storedActivity, userLat, userLon)
        }

        ActivityMemoryCache.snapshot().firstOrNull { it.id == activityId }
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
        var cached = ActivityMemoryCache.snapshot()

        if (cached.isEmpty() && activityDao != null) {
            val dbEntities = activityDao.getAllActivities().first()
            if (dbEntities.isNotEmpty()) {
                val mappedDomain = dbEntities.map { it.toActivity() }
                ActivityMemoryCache.putAll(mappedDomain)
                cached = mappedDomain
            }
        }

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

    private fun parseRecommendations(
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
            activities.add(activity)
        }

        return activities
    }

    private fun parseNearbyActivities(json: String, userLat: Double, userLon: Double): List<Activity> {
        val array = JSONArray(json)
        val activities = mutableListOf<Activity>()

        for (i in 0 until array.length()) {
            val activityJson = array.getJSONObject(i)
            activities.add(parseSingleActivity(activityJson, userLat = userLat, userLon = userLon))
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