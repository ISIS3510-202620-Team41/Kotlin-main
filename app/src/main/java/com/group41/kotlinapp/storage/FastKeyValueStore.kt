package com.group41.kotlinapp.storage

import android.content.Context
import android.util.Log
import com.tencent.mmkv.MMKV

/**
 * La base de datos llave/valor de la app, construida sobre MMKV.
 *
 * ¿Por que una tercera forma de guardar datos, si ya hay Room y SharedPreferences?
 *
 * Porque los tres resuelven problemas distintos y este proyecto necesita los tres:
 *
 * - Room es una base relacional: sirve cuando hay que consultar, filtrar y relacionar
 *   filas. Guardar un solo par "ultima ubicacion" en una tabla seria usar un camion
 *   para llevar una carta.
 *
 * - SharedPreferences guarda un archivo XML y lo carga completo en memoria. Es perfecto
 *   para ajustes que se escriben de vez en cuando, pero cada escritura reescribe el
 *   archivo entero. Si se guardara la ubicacion cada vez que el sensor la reporta
 *   (varias veces por minuto mientras la pantalla de cercania esta abierta), se estaria
 *   reescribiendo el XML completo una y otra vez.
 *
 * - MMKV es una base llave/valor que usa `mmap`: el sistema operativo mapea el archivo
 *   directamente a memoria, y escribir una clave solo agrega esa clave al final del
 *   archivo en vez de regenerarlo. Por eso soporta escrituras muy frecuentes.
 *
 * Es la misma categoria de herramienta que Hive en Flutter o Realm: una base de datos
 * sin SQL, donde se guarda y se lee por clave.
 *
 * Lo que vive aqui tiene dos cosas en comun: se escribe muy seguido y no importa
 * perderlo si el usuario desinstala la app.
 */
object FastKeyValueStore {

    /**
     * MMKV permite tener varios archivos separados. Se usa uno con nombre propio en vez
     * del de por defecto para que los datos de la app no se mezclen con los de ninguna
     * libreria que tambien use MMKV.
     */
    private const val STORE_ID = "llamalla_fast_store"

    private const val KEY_LAST_LATITUDE = "lastLocation.latitude"
    private const val KEY_LAST_LONGITUDE = "lastLocation.longitude"
    private const val KEY_LAST_SAVED_AT = "lastLocation.savedAt"

    /**
     * @Volatile porque `initialize` corre en el hilo principal al arrancar la app, pero
     * las lecturas ocurren en hilos de entrada/salida. Sin esto, un hilo podria no ver
     * todavia el valor que otro acaba de escribir.
     */
    @Volatile
    private var store: MMKV? = null

    /**
     * Prepara MMKV. Hay que llamarlo una sola vez, al arrancar la aplicacion, antes de
     * cualquier lectura o escritura.
     *
     * Si fallara (por ejemplo, porque la libreria nativa no se pudo cargar en un
     * dispositivo raro), no se lanza la excepcion: se deja `store` en null y todas las
     * funciones de abajo se vuelven inofensivas. Un almacen de datos de conveniencia no
     * debe poder impedir que la app arranque.
     */
    fun initialize(context: Context) {
        store = try {
            MMKV.initialize(context)
            MMKV.mmkvWithID(STORE_ID)
        } catch (error: Throwable) {
            Log.e("FastKeyValueStore", "MMKV no se pudo inicializar", error)
            null
        }
    }

    /**
     * Guarda la ultima posicion que el sensor logro medir.
     *
     * Se llama cada vez que hay una medicion buena, aunque el usuario no haya pedido
     * nada: es precisamente ese historial el que permite responder cuando mas adelante
     * el sensor falle.
     */
    fun saveLastKnownLocation(latitude: Double, longitude: Double) {
        val kv = store ?: return
        // Las tres claves se escriben juntas. MMKV no tiene transacciones, pero la marca
        // de tiempo se guarda al final a proposito: si el proceso muriera entre medias,
        // quedarian coordenadas sin fecha, y `lastKnownLocation` las descarta por no
        // tener fecha en vez de devolver una posicion de origen desconocido.
        kv.encode(KEY_LAST_LATITUDE, latitude)
        kv.encode(KEY_LAST_LONGITUDE, longitude)
        kv.encode(KEY_LAST_SAVED_AT, System.currentTimeMillis())
    }

    /**
     * La ultima posicion guardada, o null si nunca se guardo ninguna.
     */
    fun lastKnownLocation(): StoredLocation? {
        val kv = store ?: return null

        // Sin fecha no se devuelve nada: quien lo use necesita saber de cuando es para
        // decidir si todavia sirve.
        val savedAt = kv.decodeLong(KEY_LAST_SAVED_AT, 0L)
        if (savedAt == 0L) return null

        return StoredLocation(
            latitude = kv.decodeDouble(KEY_LAST_LATITUDE, 0.0),
            longitude = kv.decodeDouble(KEY_LAST_LONGITUDE, 0.0),
            savedAt = savedAt
        )
    }

    /**
     * Suma uno a un contador y devuelve el valor nuevo.
     *
     * Se usa para llevar la cuenta de cosas que pasan muchas veces (busquedas hechas,
     * aciertos de cache). Este es el caso donde MMKV se gana su sitio: con
     * SharedPreferences, cada incremento reescribiria el XML completo.
     */
    fun increment(counterKey: String): Long {
        val kv = store ?: return 0L
        val next = kv.decodeLong(counterKey, 0L) + 1
        kv.encode(counterKey, next)
        return next
    }

    /** Lo que vale un contador ahora mismo. */
    fun counter(counterKey: String): Long = store?.decodeLong(counterKey, 0L) ?: 0L

    /**
     * Guarda de golpe los cuatro contadores de un cache.
     *
     * Se escriben juntos y no uno a uno desde quien llama para que la clave la construya
     * un solo sitio: si el nombre de una clave se escribiera distinto al guardar y al
     * leer, el contador se perderia sin que nada fallara.
     */
    fun putCounters(prefix: String, hits: Long, misses: Long, stale: Long, evictions: Long) {
        val kv = store ?: return
        kv.encode("$prefix.hits", hits)
        kv.encode("$prefix.misses", misses)
        kv.encode("$prefix.stale", stale)
        kv.encode("$prefix.evictions", evictions)
    }

    /**
     * Cuanto ocupa el archivo en disco, en bytes.
     *
     * MMKV reserva espacio por paginas, asi que este numero es el tamaño del archivo,
     * no la suma de los datos guardados. Se expone para poder mostrarlo en los ajustes.
     */
    fun sizeBytes(): Long = store?.totalSize() ?: 0L

    /** Borra todo lo guardado. Lo usa el boton de limpiar datos de los ajustes. */
    fun clearAll() {
        store?.clearAll()
    }
}

/**
 * Una posicion guardada, junto con el momento en que se guardo.
 *
 * La fecha va dentro del propio dato, y no aparte, porque una ubicacion sin su edad no
 * se puede usar con criterio: quien la reciba tiene que poder decidir si veinte minutos
 * son aceptables o si ya es demasiado vieja.
 */
data class StoredLocation(
    val latitude: Double,
    val longitude: Double,
    val savedAt: Long
) {
    /** Cuanto tiempo ha pasado desde que se guardo, en milisegundos. */
    val ageMillis: Long get() = System.currentTimeMillis() - savedAt
}
