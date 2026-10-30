package com.group41.kotlinapp.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Avisa en tiempo real si el telefono tiene internet o no.
 *
 * ¿Por que hacia falta esto si ya existia ApiClient.isOnline()?
 * Porque `isOnline()` es una foto: responde "¿hay internet AHORA?" en el momento exacto
 * en que se le pregunta. Sirve para decidir si mandar una peticion, pero no sirve para
 * enterarse de que la conexion volvio, porque nadie le esta preguntando en ese instante.
 *
 * Esta clase es lo contrario: es un aviso. En vez de preguntar cada rato (lo que gastaria
 * bateria y ademas siempre llegaria tarde), le pedimos al sistema operativo que NOS avise
 * cuando algo cambie. Android nos llama, no al reves.
 *
 * Eso es lo que permite el comportamiento que pide el issue: el usuario se inscribe sin
 * internet, guarda el telefono, y cuando el wifi vuelve la app se entera sola y manda la
 * inscripcion que tenia pendiente, sin que el usuario tenga que abrir nada.
 */
class ConnectivityObserver(context: Context) {

    // applicationContext y no el de la pantalla: este observador puede vivir mas que
    // cualquier Activity, y guardarse el contexto de una pantalla que ya se cerro es la
    // forma tipica de dejar memoria colgada (memory leak).
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Un flujo (Flow) de valores true/false: true = hay internet, false = no hay.
     *
     * Un Flow es como una lista que todavia no termino de escribirse: quien lo escucha va
     * recibiendo los valores nuevos a medida que aparecen. Aqui aparece un valor cada vez
     * que la conexion cambia.
     *
     * Se usa `callbackFlow` porque Android avisa de estos cambios "a la antigua", llamando
     * a los metodos de un objeto que uno le registra. callbackFlow es el puente que
     * convierte ese estilo de avisos en un Flow que las corrutinas pueden consumir.
     */
    val isOnline: Flow<Boolean> = callbackFlow {

        val callback = object : ConnectivityManager.NetworkCallback() {

            /** Aparecio una red utilizable (se conecto el wifi, o volvio la señal). */
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            /** Se perdio la red que estabamos usando (modo avion, se salio del wifi). */
            override fun onLost(network: Network) {
                // No basta con decir false: el telefono puede tener wifi Y datos moviles.
                // Si se cae el wifi pero quedan los datos, seguimos online. Por eso se
                // vuelve a consultar el estado real en vez de asumir que nos quedamos sin nada.
                trySend(currentlyOnline())
            }

            /**
             * La red sigue ahi pero cambio de que es capaz.
             *
             * El caso tipico es el wifi de un cafe o una universidad: el telefono esta
             * conectado al wifi, pero hasta que no aceptes la pantalla de login ese wifi
             * no lleva a internet de verdad. Android lo reporta aqui, y para nosotros
             * "conectado pero sin internet" tiene que contar como estar offline, porque
             * las peticiones al backend van a fallar igual.
             */
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.isUsable())
            }
        }

        // Solo nos interesan las redes que dicen llevar a internet; una conexion por USB
        // o a un dispositivo bluetooth no nos sirve para hablar con el backend.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // El primer valor hay que mandarlo a mano: los avisos de Android solo llegan cuando
        // algo CAMBIA, y si el telefono ya estaba conectado desde antes no va a cambiar nada.
        // Sin esta linea, quien escuche el flujo se quedaria esperando en blanco.
        trySend(currentlyOnline())

        // awaitClose se ejecuta cuando quien escuchaba deja de escuchar (por ejemplo, se
        // cerro la pantalla). Si no quitaramos el registro, Android seguiria llamandonos
        // para siempre y gastando bateria por una pantalla que ya no existe.
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
        // Android a veces avisa dos o tres veces seguidas lo mismo (por ejemplo, al cambiar
        // de wifi a datos). distinctUntilChanged descarta los repetidos, para que la UI no
        // parpadee ni se dispare la sincronizacion varias veces por un mismo evento.
        .distinctUntilChanged()

    /** ¿Hay internet en este preciso momento? Se usa para el valor inicial y al perder una red. */
    fun currentlyOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.isUsable()
    }

    /**
     * Una red sirve cuando dice llevar a internet (NET_CAPABILITY_INTERNET) y ademas Android
     * ya comprobo que de verdad llega (NET_CAPABILITY_VALIDATED).
     *
     * Lo segundo es lo que distingue el wifi del aeropuerto, al que estas conectado pero que
     * no te deja salir hasta que aceptes sus terminos, de un wifi que funciona de verdad.
     */
    private fun NetworkCapabilities.isUsable(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
