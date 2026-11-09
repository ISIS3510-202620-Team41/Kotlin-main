package com.group41.kotlinapp.repository

import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.ApiService
import com.group41.kotlinapp.network.FreeSlotDto
import com.group41.kotlinapp.network.FriendScheduleDto
import com.group41.kotlinapp.network.UserDto
import java.util.concurrent.ConcurrentHashMap

/**
 * Repositorio de Horarios de Amigos y Disponibilidad Compartida.
 *
 * Implementa conectividad eventual:
 * Mantiene una caché en memoria y fallback para modo avión/offline, de forma que el usuario
 * pueda seguir consultando la disponibilidad de sus amigos y calculando huecos libres
 * incluso sin conexión a internet.
 */
interface FriendScheduleRepository {
    suspend fun getFriends(): List<UserDto>
    suspend fun getFriendSchedules(date: String): List<FriendScheduleDto>
    suspend fun getMyFreeSlots(date: String): List<FreeSlotDto>
    fun getCachedFriends(): List<UserDto>
    fun getCachedFriendSchedules(date: String): List<FriendScheduleDto>?
}

class ApiFriendScheduleRepository(
    private val api: ApiService = ApiClient.api
) : FriendScheduleRepository {

    // Caché en memoria hilo-segura para garantizar lectura < 1ms
    private val cachedFriends = mutableListOf<UserDto>()
    private val scheduleCacheByDate = ConcurrentHashMap<String, List<FriendScheduleDto>>()
    private val mySlotsCacheByDate = ConcurrentHashMap<String, List<FreeSlotDto>>()

    override suspend fun getFriends(): List<UserDto> {
        return try {
            val list = api.listFriends()
            synchronized(cachedFriends) {
                cachedFriends.clear()
                cachedFriends.addAll(list)
            }
            list
        } catch (e: Exception) {
            // Fallback en caso de estar offline o sin red
            getCachedFriends()
        }
    }

    override suspend fun getFriendSchedules(date: String): List<FriendScheduleDto> {
        return try {
            val list = api.friendSchedules(date)
            scheduleCacheByDate[date] = list
            list
        } catch (e: Exception) {
            // Fallback a los datos cacheados
            scheduleCacheByDate[date] ?: emptyList()
        }
    }

    override suspend fun getMyFreeSlots(date: String): List<FreeSlotDto> {
        return try {
            val gaps = api.myGaps(date).free
            mySlotsCacheByDate[date] = gaps
            gaps
        } catch (e: Exception) {
            mySlotsCacheByDate[date] ?: emptyList()
        }
    }

    override fun getCachedFriends(): List<UserDto> {
        return synchronized(cachedFriends) {
            cachedFriends.toList()
        }
    }

    override fun getCachedFriendSchedules(date: String): List<FriendScheduleDto>? {
        return scheduleCacheByDate[date]
    }
}
