package com.group41.kotlinapp.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Los ajustes que el usuario elige, guardados con SharedPreferences.
 *
 * Por que SharedPreferences y no las otras dos opciones que ya existen en el proyecto:
 *
 * - No Room: esto no son filas que haya que consultar ni relacionar. Son cuatro valores
 *   sueltos. Una tabla con una sola fila y cuatro columnas seria una base de datos usada
 *   como si fuera un par clave-valor.
 *
 * - No DataStore: DataStore devuelve `Flow` y sus lecturas son `suspend`, que es lo
 *   correcto para datos que cambian y a los que la pantalla se suscribe. Pero estos
 *   valores hacen falta antes de dibujar: el radio por defecto hay que conocerlo en el
 *   momento en que se arma la pantalla de busqueda, no un instante despues. Con DataStore
 *   habria que dibujar primero un valor inventado y corregirlo cuando llegara el real, y
 *   el usuario veria el radio saltar de un numero a otro.
 *
 * - No MMKV: estos ajustes se escriben cuando el usuario toca un control, o sea casi
 *   nunca. La ventaja de MMKV es aguantar escrituras muy frecuentes, y aqui no hay
 *   ninguna que aguantar.
 *
 * SharedPreferences carga el XML completo en memoria la primera vez y despues lee de ahi,
 * asi que `getFloat` es inmediato. Eso es exactamente lo que hace falta.
 */
class AppSettings(context: Context) {

    /**
     * MODE_PRIVATE es el unico modo valido: los otros (MODE_WORLD_READABLE y
     * MODE_WORLD_WRITEABLE) lanzan excepcion desde Android 7, porque dejaban que
     * cualquier otra app leyera el archivo.
     */
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * El radio que la pantalla de cercania propone al abrirse, en kilometros.
     *
     * Se guarda para no obligar al usuario a ajustarlo cada vez. Alguien que siempre busca
     * a dos kilometros no tiene por que repetir ese gesto en cada sesion.
     */
    var defaultRadiusKm: Float
        get() = prefs.getFloat(KEY_DEFAULT_RADIUS, DEFAULT_RADIUS_KM)
        // apply() y no commit(): escribe en memoria ya y manda el disco a un hilo de
        // fondo. Quien lea el valor justo despues ve el nuevo, porque la copia en memoria
        // se actualiza de inmediato; lo unico que se aplaza es llegar al archivo. Para un
        // ajuste eso sobra. commit() solo se justifica cuando el proceso se esta muriendo
        // y no habria hilo de fondo que terminara el trabajo.
        set(value) = prefs.edit().putFloat(KEY_DEFAULT_RADIUS, value).apply()

    /** Mostrar las distancias en millas en vez de en metros y kilometros. */
    var useMiles: Boolean
        get() = prefs.getBoolean(KEY_USE_MILES, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_MILES, value).apply()

    /** Avisar al usuario cuando una inscripcion pendiente por fin llega al servidor. */
    var notifyOnSync: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ON_SYNC, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_ON_SYNC, value).apply()

    /**
     * Si ya se le explico al usuario que la app funciona sin internet.
     *
     * Es una bandera de las que justifican que exista este almacenamiento: tiene que
     * sobrevivir al cierre de la app, porque explicar lo mismo en cada arranque seria
     * molesto, y no tiene sentido guardarla en el servidor.
     */
    var hasSeenOfflineTip: Boolean
        get() = prefs.getBoolean(KEY_SEEN_OFFLINE_TIP, false)
        set(value) = prefs.edit().putBoolean(KEY_SEEN_OFFLINE_TIP, value).apply()

    /**
     * Deja todos los ajustes como venian de fabrica.
     *
     * `clear()` borra las claves en vez de escribir los valores por defecto uno a uno.
     * Es mejor asi: si mañana se agrega un ajuste nuevo, este metodo lo cubre sin que
     * nadie se acuerde de venir a tocarlo.
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }

    private companion object {
        /**
         * Nombre propio, distinto del de la analitica y del de la sesion.
         *
         * Cada archivo se carga completo en memoria al abrirlo, asi que separarlos
         * significa que leer un ajuste no carga la cola de eventos de analitica, y que
         * limpiar los ajustes no puede tocar por error las credenciales.
         */
        const val PREFS_NAME = "app_settings"

        const val KEY_DEFAULT_RADIUS = "defaultRadiusKm"
        const val KEY_USE_MILES = "useMiles"
        const val KEY_NOTIFY_ON_SYNC = "notifyOnSync"
        const val KEY_SEEN_OFFLINE_TIP = "hasSeenOfflineTip"

        /** Dos kilometros: lo que se camina en veinte minutos. */
        const val DEFAULT_RADIUS_KM = 2f
    }
}
