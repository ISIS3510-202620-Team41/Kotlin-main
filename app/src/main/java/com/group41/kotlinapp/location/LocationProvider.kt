package com.group41.kotlinapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.group41.kotlinapp.storage.FastKeyValueStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Donde esta el usuario, leido del sensor de verdad del telefono.
 *
 * Antes la pantalla de busqueda por cercania pedia que el usuario escribiera su latitud y
 * su longitud a mano en dos casillas. Eso servia para probar, pero nadie se sabe sus
 * coordenadas, asi que en la practica la funcion no se podia usar.
 *
 * Esta clase lo reemplaza por el sensor: el telefono dice donde esta y la app busca
 * actividades alrededor de ese punto.
 *
 * Usa FusedLocationProviderClient, el servicio de ubicacion de Google, en vez del
 * LocationManager que trae Android a secas. La diferencia es que "fused" significa
 * fusionado: combina el GPS, las antenas de telefonia y las redes wifi cercanas en una
 * sola respuesta. Eso da una posicion mas rapida y gasta menos bateria que encender el
 * GPS y esperar a que vea los satelites, sobre todo bajo techo, donde el GPS solo
 * practicamente no funciona.
 */
class LocationProvider(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Pide la posicion actual.
     *
     * Es una funcion `suspend`, es decir, que puede tardar y mientras tanto libera el hilo
     * en vez de bloquearlo. Hace falta porque conseguir una posicion no es inmediato: puede
     * tomar unos segundos si el telefono lleva rato sin mirar donde esta.
     */
    @SuppressLint("MissingPermission")  // El permiso se comprueba en la linea de abajo.
    suspend fun current(): LocationResult {
        // Declarar el permiso en el manifest no basta. La ubicacion es un permiso
        // "peligroso": el usuario tiene que aceptarlo expresamente, y puede quitarlo
        // despues desde los ajustes. Por eso se comprueba cada vez y no una sola vez al
        // arrancar la app.
        if (!hasPermission()) return LocationResult.PermissionDenied

        // Primero se mira la ultima posicion conocida, que el sistema ya tiene guardada de
        // cuando otra app pregunto. Es instantanea y casi siempre sirve: para buscar
        // actividades en un radio de kilometros, da igual si el usuario camino media cuadra
        // desde la ultima medicion.
        lastKnown()?.let { return remember(it) }

        // Si no hay ninguna guardada (telefono recien prendido, o nunca se ha pedido),
        // toca medir de cero.
        val measured = freshFix()
        if (measured is LocationResult.Available) return remember(measured)

        // Ni el sistema tenia una posicion guardada ni los sensores pudieron medir. Antes
        // de rendirse se recurre a la ultima que guardo esta app por su cuenta.
        //
        // Este es el caso que lo justifica: el usuario entra al metro o a un sotano, se
        // queda sin red y sin señal de GPS a la vez. La busqueda por cercania funciona
        // sin internet, pero no funciona sin saber donde esta el usuario, asi que sin
        // esta copia se quedaria igual de bloqueada que si no hubiera modo sin conexion.
        //
        // La posicion guardada no se hace pasar por una medicion fresca: se devuelve como
        // [LocationResult.Stale], con su edad, y la pantalla lo avisa.
        return storedFallback() ?: measured
    }

    /**
     * Guarda la posicion medida en la base llave/valor y la devuelve tal cual.
     *
     * Se guarda en cada medicion buena, aunque nadie la haya pedido para esto. Es
     * justamente lo que hace que mas tarde, cuando el sensor falle, haya algo que ofrecer.
     *
     * Va en MMKV y no en una tabla porque es un solo par de numeros que se reescribe
     * constantemente, no filas que haya que consultar ni relacionar.
     */
    private fun remember(result: LocationResult.Available): LocationResult.Available {
        FastKeyValueStore.saveLastKnownLocation(result.latitude, result.longitude)
        return result
    }

    /**
     * La ultima posicion que esta app guardo, si todavia sirve de algo.
     *
     * El limite de 24 horas no es arbitrario: en un dia una persona puede haber cambiado
     * de ciudad, y ofrecerle actividades cerca de donde estaba ayer seria peor que no
     * ofrecerle nada, porque se veria como un error de la app. Por debajo de ese limite
     * lo normal es que siga en la misma zona, y de todas formas la pantalla le dice de
     * cuando es la posicion para que el decida.
     */
    private fun storedFallback(): LocationResult.Stale? =
        FastKeyValueStore.lastKnownLocation()
            ?.takeIf { it.ageMillis <= MAX_STORED_AGE_MILLIS }
            ?.let { stored ->
                LocationResult.Stale(
                    latitude = stored.latitude,
                    longitude = stored.longitude,
                    ageMillis = stored.ageMillis
                )
            }

    /** ¿El usuario nos dejo ver su ubicacion, en cualquiera de los dos niveles? */
    fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            // Basta con la aproximada. Exigir la precisa seria pedir mas de lo necesario:
            // con un radio de busqueda de kilometros, unos cientos de metros de error no
            // cambian que actividades caen dentro.
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * La ultima posicion que el sistema tenga guardada. Null si no hay ninguna.
     */
    @SuppressLint("MissingPermission")
    private suspend fun lastKnown(): LocationResult.Available? =
        suspendCancellableCoroutine { continuation ->
            client.lastLocation
                .addOnSuccessListener { location ->
                    continuation.resume(
                        location?.let { LocationResult.Available(it.latitude, it.longitude) }
                    )
                }
                // Si falla se devuelve null en vez de propagar el error, porque este es solo
                // el atajo rapido: quien llama seguira con la medicion de cero.
                .addOnFailureListener { continuation.resume(null) }
        }

    /**
     * Enciende los sensores y mide la posicion ahora mismo.
     */
    @SuppressLint("MissingPermission")
    private suspend fun freshFix(): LocationResult =
        suspendCancellableCoroutine { continuation ->
            // El "cancellation token" permite abortar la medicion. Se usa abajo para que,
            // si el usuario cierra la pantalla mientras el GPS esta buscando, los sensores
            // se apaguen en vez de seguir gastando bateria por nadie.
            val cancellation = CancellationTokenSource()

            client.getCurrentLocation(
                // BALANCED en vez de HIGH_ACCURACY: le dice al sistema que puede usar
                // antenas y wifi en lugar de encender el GPS. Responde en uno o dos
                // segundos en vez de diez o mas, y la precision que da (decenas de metros)
                // sobra para esta busqueda. HIGH_ACCURACY tendria sentido para una
                // navegacion paso a paso, no para "que hay cerca de mi".
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellation.token
            )
                .addOnSuccessListener { location ->
                    continuation.resume(
                        if (location != null) {
                            LocationResult.Available(location.latitude, location.longitude)
                        } else {
                            // Puede pasar con la ubicacion apagada en los ajustes, o en un
                            // sotano sin señal de ningun tipo.
                            LocationResult.Unavailable
                        }
                    )
                }
                .addOnFailureListener { continuation.resume(LocationResult.Unavailable) }

            // Se ejecuta si quien pidio la ubicacion desaparece (por ejemplo, el usuario
            // salio de la pantalla). Apaga la medicion en curso.
            continuation.invokeOnCancellation { cancellation.cancel() }
        }

    private companion object {
        /** Mas vieja que esto, la posicion guardada se descarta. */
        const val MAX_STORED_AGE_MILLIS = 24L * 60 * 60 * 1000
    }
}

/**
 * Como termino el intento de saber donde esta el usuario.
 *
 * Son tres casos y no dos ("hay posicion" / "no hay") porque la pantalla tiene que
 * responder distinto a cada uno:
 * - Sin permiso, lo util es ofrecerle al usuario concederlo.
 * - Sin señal, lo util es decirle que lo intente en otro sitio o escriba el punto a mano.
 * Mezclarlos en un unico "no se pudo" dejaria al usuario sin saber que hacer.
 */
sealed interface LocationResult {

    /** Se sabe donde esta. */
    data class Available(val latitude: Double, val longitude: Double) : LocationResult

    /** El usuario todavia no ha concedido el permiso, o lo quito. */
    data object PermissionDenied : LocationResult

    /** Hay permiso, pero el telefono no logro medir: ubicacion apagada o sin señal. */
    data object Unavailable : LocationResult

    /**
     * No se pudo medir ahora, pero la app tenia guardada una posicion anterior y se usa esa.
     *
     * Es un caso aparte de [Available] a proposito. Podrian devolverse las coordenadas sin
     * mas y la busqueda funcionaria igual, pero entonces la pantalla no tendria forma de
     * saber que son de antes, y le estaria diciendo al usuario "esto esta cerca de ti"
     * cuando en realidad es "esto estaba cerca de donde estabas".
     *
     * Al ser un caso propio del sealed interface, el compilador obliga a cada "when" que
     * lo consuma a decidir que hacer con el. No se puede ignorar por descuido.
     */
    data class Stale(
        val latitude: Double,
        val longitude: Double,
        /** Cuanto tiempo paso desde que se guardo, en milisegundos. */
        val ageMillis: Long
    ) : LocationResult
}
