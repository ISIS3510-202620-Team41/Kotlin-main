package com.group41.kotlinapp.repository

import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.ApiService
import com.group41.kotlinapp.network.FreeSlotDto
import com.group41.kotlinapp.network.GoogleSyncRequest
import com.group41.kotlinapp.network.SyncResult

/**
 * Horario del usuario: calendarios externos y ventanas libres.
 *
 * Es una interfaz para que ScheduleViewModel se pueda probar con una versión
 * falsa, sin backend.
 */
interface ScheduleRepository {
    /** Bandera local: el backend no tiene un endpoint de "estado de conexión" */
    fun googleConnected(): Boolean
    fun setGoogleConnected(connected: Boolean)

    /** Primera conexión: el backend canjea el código, guarda el token e importa */
    suspend fun connectGoogle(authCode: String): SyncResult

    /** Re-sincroniza con el token que el backend ya guardó, sin pasar por Google */
    suspend fun resyncGoogle(): SyncResult

    /** `date` en formato yyyy-MM-dd */
    suspend fun freeSlots(date: String): List<FreeSlotDto>
}

class ApiScheduleRepository(
    private val api: ApiService = ApiClient.api
) : ScheduleRepository {

    override fun googleConnected() = ApiClient.tokens.googleConnected

    override fun setGoogleConnected(connected: Boolean) {
        ApiClient.tokens.googleConnected = connected
    }

    override suspend fun connectGoogle(authCode: String): SyncResult {
        val result = api.syncGoogle(GoogleSyncRequest(authCode))
        setGoogleConnected(true)
        return result
    }

    override suspend fun resyncGoogle(): SyncResult {
        val result = api.refreshGoogle()
        setGoogleConnected(true)
        return result
    }

    override suspend fun freeSlots(date: String) = api.myGaps(date).free
}