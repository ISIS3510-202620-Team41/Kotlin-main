package com.group41.kotlinapp.model

import java.io.Serializable

/**
 * Modelo de dominio para una actividad en Llamalla.
 *
 * Contiene todos los campos del prototipo oficial de Figma:
 * duración calculada, distancia, precio, capacidad máxima, inscritos,
 * descripción del lugar e imagen para renderizado offline.
 */
data class Activity(
    val id: String,
    val name: String,
    val category: String,
    val durationMinutes: Int,
    val distanceMeters: Int,
    val score: Double = 0.0,
    val joined: Boolean = false,
    val recommendationId: String? = null,
    val freeTimeMinutes: Int? = null,
    val price: String = "Gratis",
    val capacity: Int? = null,
    val participants: Int = 0,
    val aboutLocation: String? = null,
    val locationName: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val imageUrl: String? = null,
    val description: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,

    /**
     * La inscripcion se guardo en el telefono pero el backend todavia no la recibio.
     *
     * Esta aqui, en el modelo que usan las pantallas, y no solo en la base de datos,
     * porque es la pantalla la que tiene que avisarle al usuario que su inscripcion
     * esta guardada pero aun no confirmada. Si este dato se quedara en la capa de datos,
     * la UI no tendria como saberlo y le mentiria al usuario diciendole "listo".
     */
    val pendingSync: Boolean = false
) : Serializable
