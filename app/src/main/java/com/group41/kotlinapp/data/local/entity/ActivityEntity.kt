package com.group41.kotlinapp.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity class representing the 'activities' table in the local Room database.
 * This acts as the offline cache for the Activity domain model.
 */
@Entity(tableName = "activities")
data class ActivityEntity(
    // Unique identifier for the activity, acts as the primary key in the database
    @PrimaryKey
    val id: String,

    // Core details preserving strict non-null types from the domain model
    val name: String,
    val category: String,
    val durationMinutes: Int,

    /** Geographical data: distanceMeters is saved as a cached value.
    *For accurate offline distance, recalculate using latitude/longitude
    *against the user's updated device location.
    */
    val distanceMeters: Int,
    val latitude: Double,
    val longitude: Double,

    val score: Double,
    val joined: Boolean,

    // Optional context values allowing nulls just like Activity.kt
    val recommendationId: String?,
    val freeTimeMinutes: Int?,

    val price: String,

    // Capacity remains Int? to handle activities with unlimited/undefined capacity
    val capacity: Int?,
    val participants: Int,

    val aboutLocation: String?,
    val locationName: String?,
    /**
     * Stores only the URL string. Actual offline image availability requires
     * a separate local file caching mechanism (like Coil/Glide offline cache).
     */

    val imageUrl: String?,

    val description: String?,
    val startTime: String?,
    val endTime: String?,

    /**
     * Marca que el usuario se inscribio a esta actividad sin internet y el backend
     * todavia no se ha enterado.
     *
     * Sirve para dos cosas:
     * 1. La pantalla puede mostrar "Inscrito (pendiente de sincronizar)" en vez de un
     *    "Inscrito" a secas que seria mentira: el servidor aun no lo sabe.
     * 2. Si el usuario cierra la app, apaga el telefono y lo prende al dia siguiente,
     *    el aviso sigue ahi, porque esto vive en el disco y no en la memoria.
     *
     * Vuelve a false cuando la sincronizacion en segundo plano confirma la inscripcion.
     *
     * Tiene valor por defecto false para que el resto del codigo, que crea actividades
     * a partir de lo que responde el backend, no tenga que mencionarlo: lo que viene del
     * servidor por definicion ya esta sincronizado.
     *
     * El `defaultValue = "0"` repite ese mismo false, pero del lado de SQLite (0 es false).
     * Hace falta porque el telefono puede llegar a esta version por dos caminos distintos:
     * instalando la app de cero, donde Room crea la tabla desde esta clase, o actualizando
     * una app que ya tenia datos, donde la columna la agrega la migracion. Declarandolo aqui,
     * los dos caminos producen exactamente la misma tabla y no hay forma de que una instalacion
     * se comporte distinto de la otra.
     */
    @ColumnInfo(defaultValue = "0")
    val pendingSync: Boolean = false
)