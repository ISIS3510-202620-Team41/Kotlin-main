package com.group41.kotlinapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Una busqueda por cercania que el usuario hizo, guardada en la tabla `search_history`.
 *
 * ¿Para que guardar esto?
 *
 * Por tres cosas que la app no podria hacer de otra forma:
 *
 * 1. Repetir una busqueda anterior con un toque, incluso sin internet.
 * 2. Ofrecerle al usuario sus zonas habituales en vez de obligarlo a empezar de cero.
 * 3. Saber si la busqueda se respondio con datos del servidor o con los del telefono,
 *    que es lo que permite medir de verdad cuanto se usa el modo sin conexion.
 *
 * Se guarda en una tabla y no en preferencias porque esto no es un valor suelto: son
 * muchas filas sobre las que hay que contar, agrupar y ordenar. Eso es exactamente lo
 * que una base relacional hace bien y una llave/valor no hace en absoluto.
 */
@Entity(
    tableName = "search_history",
    // El indice es por los dos campos juntos y en este orden porque las consultas
    // siempre filtran por usuario y despues ordenan por fecha. Un indice solo sobre
    // `userId` obligaria a SQLite a ordenar los resultados a mano cada vez.
    indices = [Index(value = ["userId", "searchedAt"])]
)
data class SearchHistoryEntity(

    /**
     * La clave la pone SQLite.
     *
     * Aqui si conviene un id automatico, al contrario que en la cola de acciones
     * pendientes, donde el id se deriva de los datos para evitar duplicados. La razon es
     * la contraria: dos busquedas iguales en momentos distintos son dos hechos distintos
     * y las dos deben quedar registradas.
     */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** De quien fue la busqueda. Dos cuentas en el mismo telefono no comparten historial. */
    val userId: String,

    /** Donde estaba el usuario al buscar. */
    val latitude: Double,
    val longitude: Double,

    /** El radio que pidio, en kilometros. */
    val radiusKm: Double,

    /** Cuantas actividades aparecieron. Un cero tambien es informacion que vale guardar. */
    val resultCount: Int,

    /**
     * Si la respuesta salio de la base local en vez del servidor.
     *
     * Es el campo mas interesante de la tabla: permite responder con datos, y no de
     * memoria, a la pregunta de cuantas veces la app sirvio al usuario sin internet.
     */
    val wasOffline: Boolean,

    /** Cuando fue, en milisegundos desde 1970. */
    val searchedAt: Long
)
