package com.group41.kotlinapp.network

data class GoogleSyncRequest(val authCode: String)
data class SyncResult(val imported: Int, val skippedEvents: Int, val calendars: Int)
data class FreeSlotDto(val start: String, val end: String)
data class GapsResponse(val date: String, val timezone: String, val free: List<FreeSlotDto>)
data class FriendScheduleDto(val friend: UserDto, val free: List<FreeSlotDto>)
data class LoginRequest(val email: String, val password: String)
data class RegisterRequest(val email: String, val password: String, val name: String)
data class RefreshRequest(val refreshToken: String)
data class UpdateProfileRequest(val name: String?, val bio: String?)

data class UserDto(
    val id: String,
    val email: String,
    val name: String,
    val bio: String?,
    val avatarUrl: String?
)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val user: UserDto
)

data class ApiError(val status: Int, val message: String?, val fields: Map<String, String>?)

/**
 * Un cupon comercial tal como lo manda el backend.
 *
 * `validUntil` viene en ISO 8601 con offset, y se deja como texto en vez de convertirlo:
 * es lo que el backend envia, es lo que se guarda en SQLite, y es comparable como cadena
 * sin convertir nada. Pasarlo a fecha aqui obligaria a volver a formatearlo para mostrarlo.
 */
data class DealDto(
    val id: String,
    val merchant: String,
    val title: String,
    val description: String?,
    val discountPercent: Int,
    val category: String,
    /** Cuantos minutos toma aprovecharlo. Es el campo que se cruza con el hueco libre. */
    val minutesNeeded: Int,
    val validUntil: String?,
    val imageUrl: String?
)

/**
 * Una interaccion con un cupon que se reporta al backend.
 *
 * `duringBreak` es `Boolean?` y no `Boolean`: tiene que poder viajar nulo cuando el
 * estudiante no ha importado su horario. Un booleano a secas lo convertiria en false y
 * meteria a esos usuarios en el grupo de control de la BQ-11.
 */
data class DealInteractionDto(
    val dealId: String,
    val type: String,
    val duringBreak: Boolean?,
    val breakMinutesLeft: Int?
)

/**
 * Varias interacciones en una sola peticion.
 *
 * Se manda en lote porque mostrar una lista de diez cupones produce diez eventos. Una
 * peticion por evento serian diez peticiones cada vez que el usuario abre la pantalla.
 */
data class DealInteractionBatch(val interactions: List<DealInteractionDto>)

/** Lo que devuelve el backend al redimir: el codigo que se ensena en el local. */
data class RedemptionDto(
    val dealId: String,
    val redemptionCode: String,
    val redeemedAt: String
)
