package com.group41.kotlinapp.repository

import com.group41.kotlinapp.cache.ActivityMemoryCache
import com.group41.kotlinapp.data.local.dao.ActivityDao
import com.group41.kotlinapp.data.local.entity.ActivityEntity
import com.group41.kotlinapp.data.local.toEntity
import com.group41.kotlinapp.model.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Prueba la busqueda por cercania que funciona sin internet.
 *
 * Es la parte central del issue: con la posicion del usuario y las actividades que el
 * telefono ya tiene guardadas, decidir cuales quedan dentro del radio, calculando las
 * distancias en un hilo aparte para no congelar la pantalla.
 *
 * Estos tests corren en el computador, sin emulador. Eso es posible porque el calculo no
 * depende de Android: es aritmetica sobre coordenadas, y la base de datos se reemplaza por
 * una de mentira que guarda las filas en una lista. El sensor de ubicacion si necesita un
 * dispositivo, pero esa parte esta separada en LocationProvider justamente para que esta,
 * que es donde vive la logica, se pueda probar aqui.
 *
 * Las coordenadas son de Bogota, alrededor de la Universidad de los Andes, para que las
 * distancias se puedan comprobar contra un mapa real.
 */
class NearbyLocalSearchTest {

    /**
     * Una base de datos de mentira: guarda las actividades en una lista en memoria.
     *
     * Solo implementa de verdad lo que esta busqueda usa. Los demas metodos existen porque
     * la interfaz los exige, pero no hacen falta aqui.
     */
    private class FakeActivityDao(private val rows: List<ActivityEntity>) : ActivityDao {
        override fun getAllActivities(): Flow<List<ActivityEntity>> = flowOf(rows)
        override suspend fun getActivityById(id: String): ActivityEntity? = rows.find { it.id == id }
        override suspend fun insertActivities(activities: List<ActivityEntity>) = Unit
        override suspend fun insertActivity(activity: ActivityEntity) = Unit
        override suspend fun clearAll() = Unit
    }

    // Universidad de los Andes, edificio Mario Laserna.
    private val userLat = 4.6018
    private val userLon = -74.0661

    @Before
    fun setUp() {
        // La cache es un objeto compartido por toda la app, asi que cada test tiene que
        // empezar con ella vacia. Si no, los datos de un test se colarian en el siguiente
        // y los fallos dependerian del orden en que se ejecuten.
        ActivityMemoryCache.clear()
    }

    /** Un repositorio con estas actividades guardadas en disco. */
    private fun repositoryWith(vararg activities: Activity) =
        ActivityRepository(activityDao = FakeActivityDao(activities.map { it.toEntity() }))

    @Test
    fun `solo devuelve lo que esta dentro del radio`() = runTest {
        val repository = repositoryWith(
            // A unos 400 m: dentro de 1 km.
            activity("cerca", lat = 4.6054, lon = -74.0661),
            // Plaza de Bolivar, a mas de 1 km.
            activity("lejos", lat = 4.5981, lon = -74.0758)
        )

        val result = repository.nearbyFromLocalDatabase(userLat, userLon, radiusKm = 1.0)

        assertEquals(listOf("cerca"), result.activities.map { it.id })
    }

    @Test
    fun `un radio mas amplio incluye lo que antes quedaba fuera`() = runTest {
        val repository = repositoryWith(
            activity("cerca", lat = 4.6054, lon = -74.0661),
            activity("lejos", lat = 4.5981, lon = -74.0758)
        )

        val result = repository.nearbyFromLocalDatabase(userLat, userLon, radiusKm = 5.0)

        assertEquals(2, result.activities.size)
    }

    @Test
    fun `las actividades salen de la mas cercana a la mas lejana`() = runTest {
        // El orden lo hace el repositorio y no la pantalla: es el orden en el que el
        // usuario las quiere ver, y calcularlo una sola vez aqui evita que cada pantalla
        // tenga que acordarse de ordenarlas.
        val repository = repositoryWith(
            activity("lejana", lat = 4.6200, lon = -74.0661),
            activity("cercana", lat = 4.6030, lon = -74.0661),
            activity("media", lat = 4.6100, lon = -74.0661)
        )

        val result = repository.nearbyFromLocalDatabase(userLat, userLon, radiusKm = 10.0)

        assertEquals(listOf("cercana", "media", "lejana"), result.activities.map { it.id })
    }

    @Test
    fun `las distancias se recalculan contra donde esta el usuario ahora`() = runTest {
        // La distancia que venia guardada se midio desde donde el usuario estaba ANTES.
        // Si se reusara, alguien que se movio de barrio veria distancias de su ubicacion
        // anterior. Aqui se guarda un valor absurdo a proposito para comprobar que se pisa.
        val repository = repositoryWith(
            activity("x", lat = 4.6054, lon = -74.0661).copy(distanceMeters = 999_999)
        )

        val result = repository.nearbyFromLocalDatabase(userLat, userLon, radiusKm = 2.0)

        val distancia = result.activities.single().distanceMeters
        assertTrue(
            "Se esperaba una distancia recalculada de unos 400 m, llego $distancia",
            distancia in 300..500
        )
    }

    @Test
    fun `las actividades sin coordenadas quedan fuera`() = runTest {
        // Una actividad en 0,0 no esta en el Golfo de Guinea: es que el servidor no mando
        // sus coordenadas. Si se colara, su distancia saldria 0 y apareceria siempre de
        // primera, por delante de las que si estan de verdad al lado del usuario.
        val repository = repositoryWith(
            activity("sin-coordenadas", lat = 0.0, lon = 0.0),
            activity("con-coordenadas", lat = 4.6054, lon = -74.0661)
        )

        val result = repository.nearbyFromLocalDatabase(userLat, userLon, radiusKm = 1.0)

        assertEquals(listOf("con-coordenadas"), result.activities.map { it.id })
    }

    @Test
    fun `sin nada guardado devuelve una lista vacia y no falla`() = runTest {
        // Es el caso de alguien que instalo la app y abrio esta pantalla sin internet:
        // nunca llego a descargar nada. Tiene que verse una lista vacia, no un cierre
        // inesperado de la app.
        val repository = repositoryWith()

        val result = repository.nearbyFromLocalDatabase(userLat, userLon, radiusKm = 1.0)

        assertTrue(result.activities.isEmpty())
    }

    @Test
    fun `se busca en todo lo guardado en disco y no solo en lo que cabe en memoria`() = runTest {
        // Este es el test que mas importa de todos.
        //
        // ActivityMemoryCache es una cache LRU con tope de 50 actividades: al llegar a 51
        // bota la que lleva mas tiempo sin consultarse. Si la busqueda leyera de ahi, un
        // usuario con 120 actividades guardadas solo veria 50, y una actividad a dos cuadras
        // no apareceria solo porque hace rato que no la abre.
        //
        // Por eso la busqueda lee del disco, que las tiene todas.
        val todas = (1..120).map { i ->
            activity("act-$i", lat = 4.6018 + i * 0.00005, lon = -74.0661)
        }
        val repository = repositoryWith(*todas.toTypedArray())

        val result = repository.nearbyFromLocalDatabase(userLat, userLon, radiusKm = 50.0)

        assertEquals(120, result.activities.size)
        assertTrue(
            "La cache en memoria no deberia poder contener las 120",
            ActivityMemoryCache.MAX_ENTRIES < 120
        )
    }

    @Test
    fun `el filtrado de muchas actividades termina en milisegundos`() = runTest {
        // El issue pide que el calculo corra en un hilo secundario y sea rapido. Este test
        // comprueba lo segundo con un volumen muy por encima del real: 2000 actividades
        // guardadas es mas de lo que un usuario llega a acumular.
        //
        // El limite de 1000 ms es deliberadamente holgado: un computador de integracion
        // continua cargado puede ser varias veces mas lento que este. Lo que se quiere
        // detectar no es una decima de diferencia, es que alguien meta sin querer una
        // consulta a la red o al disco dentro del bucle, que llevaria esto a segundos.
        val muchas = (1..2000).map { i ->
            activity("act-$i", lat = 4.60 + i * 0.0001, lon = -74.06)
        }
        val repository = repositoryWith(*muchas.toTypedArray())

        val result = repository.nearbyFromLocalDatabase(userLat, userLon, radiusKm = 50.0)

        assertEquals(2000, result.activities.size)
        assertTrue(
            "El filtrado tardo ${result.elapsedMillis} ms",
            result.elapsedMillis < 1000
        )
    }

    private fun activity(id: String, lat: Double, lon: Double) = Activity(
        id = id,
        name = "Actividad $id",
        category = "Deportes",
        durationMinutes = 60,
        distanceMeters = 0,
        latitude = lat,
        longitude = lon
    )
}
