package com.group41.kotlinapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.App
import com.group41.kotlinapp.location.LocationProvider
import com.group41.kotlinapp.location.LocationResult
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.repository.ActivityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Todo lo que la pantalla de busqueda por cercania necesita mostrar, en un solo objeto.
 *
 * Esta junto y no en seis variables sueltas para que la pantalla no pueda quedar en un
 * estado imposible, como "buscando" y "con error" al mismo tiempo. Cada vez que algo
 * cambia se publica un estado completo y coherente.
 */
data class NearbyUiState(
    /** Se esta leyendo el sensor o buscando actividades. */
    val searching: Boolean = false,

    /** Lo encontrado, de lo mas cercano a lo mas lejano. */
    val activities: List<Activity> = emptyList(),

    /** Ya se busco al menos una vez. Sirve para no decir "no hay nada" antes de buscar. */
    val hasSearched: Boolean = false,

    /** El usuario no ha dado permiso de ubicacion. La pantalla ofrece pedirlo. */
    val needsPermission: Boolean = false,

    /** Algo salio mal y hay que contarselo al usuario. */
    val error: String? = null,

    /**
     * Los resultados salieron de la base local, sin preguntarle al servidor.
     * La pantalla lo avisa para que el usuario sepa que puede haber actividades nuevas
     * que todavia no ha visto.
     */
    val fromLocalData: Boolean = false,

    /**
     * Cuanto tardo el filtrado local, en milisegundos.
     *
     * Se muestra en pantalla a proposito: es la prueba visible de que el calculo de
     * distancias corre fuera del hilo de la interfaz y aun asi termina al instante.
     */
    val elapsedMillis: Long? = null
)

/**
 * La logica de la Vista 3: buscar actividades alrededor de donde esta el usuario.
 *
 * Antes esta pantalla pedia que el usuario escribiera su latitud y longitud a mano.
 * Ahora la posicion sale del sensor del telefono y la busqueda funciona con o sin internet.
 */
class NearbyActivitiesViewModel(application: Application) : AndroidViewModel(application) {

    private val activityRepository = ActivityRepository(
        activityDao = (application as App).database.activityDao()
    )

    private val locationProvider = LocationProvider(application)

    private val _state = MutableStateFlow(NearbyUiState())
    val state: StateFlow<NearbyUiState> = _state.asStateFlow()

    /** ¿Ya tenemos permiso? La pantalla lo consulta para decidir si pedirlo. */
    fun hasLocationPermission(): Boolean = locationProvider.hasPermission()

    /**
     * Busca actividades alrededor del usuario dentro del radio indicado.
     *
     * El recorrido completo:
     * 1. Se le pregunta al sensor donde esta el usuario.
     * 2. Con internet, se le piden las actividades al servidor.
     * 3. Sin internet (o si el servidor falla), se filtran las que ya estan guardadas
     *    en el telefono, calculando las distancias en un hilo aparte.
     */
    fun search(radiusKm: Double) {
        // Si ya hay una busqueda en marcha se ignora el toque. Sin esto, tocar el boton
        // tres veces seguidas encenderia el GPS tres veces y las tres respuestas se
        // pisarian entre si al llegar en desorden.
        if (_state.value.searching) return

        _state.update { it.copy(searching = true, error = null, needsPermission = false) }

        viewModelScope.launch {
            when (val location = locationProvider.current()) {

                is LocationResult.PermissionDenied ->
                    // No es un error que mostrar en rojo: es algo que el usuario puede
                    // resolver con un toque, asi que la pantalla le ofrece el permiso.
                    _state.update { it.copy(searching = false, needsPermission = true) }

                is LocationResult.Unavailable ->
                    _state.update {
                        it.copy(
                            searching = false,
                            error = "No se pudo obtener tu ubicacion. " +
                                "Revisa que la ubicacion del telefono este encendida."
                        )
                    }

                is LocationResult.Available ->
                    loadAround(location.latitude, location.longitude, radiusKm)
            }
        }
    }

    private suspend fun loadAround(latitude: Double, longitude: Double, radiusKm: Double) {
        // Sin conexion ni se intenta la peticion: se va directo a lo guardado. Intentarla
        // solo haria esperar al usuario hasta que se agote el tiempo de espera.
        if (!ApiClient.isOnline()) {
            showLocalResults(latitude, longitude, radiusKm)
            return
        }

        try {
            val activities = activityRepository.getNearbyActivities(
                latitude = latitude,
                longitude = longitude,
                radiusKm = radiusKm
            )
            _state.update {
                it.copy(
                    searching = false,
                    hasSearched = true,
                    activities = activities,
                    fromLocalData = false,
                    elapsedMillis = null
                )
            }
        } catch (e: Exception) {
            // El servidor no respondio. En vez de dejar al usuario con un error y la
            // pantalla vacia, se le muestra lo que el telefono ya tenia guardado.
            showLocalResults(latitude, longitude, radiusKm)
        }
    }

    /**
     * Busca solo en lo que el telefono ya tiene guardado.
     *
     * Aqui es donde vive el comportamiento offline de esta vista: las actividades salen de
     * la base de datos local y las distancias se recalculan contra la posicion actual del
     * usuario, todo en un hilo secundario para que la pantalla no se congele.
     */
    private suspend fun showLocalResults(latitude: Double, longitude: Double, radiusKm: Double) {
        val result = activityRepository.nearbyFromLocalDatabase(latitude, longitude, radiusKm)
        _state.update {
            it.copy(
                searching = false,
                hasSearched = true,
                activities = result.activities,
                fromLocalData = true,
                elapsedMillis = result.elapsedMillis,
                // Si no habia nada guardado, el usuario necesita saber por que la lista
                // esta vacia: no es que no haya actividades cerca, es que nunca se
                // descargaron y ahora no hay como pedirlas.
                error = if (result.activities.isEmpty()) {
                    "Sin conexion y no hay actividades guardadas en este telefono."
                } else {
                    null
                }
            )
        }
    }

    /** El usuario acaba de responder al dialogo de permiso. */
    fun onPermissionResult(granted: Boolean, radiusKm: Double) {
        if (granted) {
            // Se repite la busqueda sola: el usuario ya expreso que queria buscar cuando
            // toco el boton, y obligarlo a tocarlo otra vez seria hacerle repetir algo
            // que ya dijo.
            search(radiusKm)
        } else {
            _state.update {
                it.copy(
                    needsPermission = false,
                    error = "Sin permiso de ubicacion no se puede buscar por cercania."
                )
            }
        }
    }
}
