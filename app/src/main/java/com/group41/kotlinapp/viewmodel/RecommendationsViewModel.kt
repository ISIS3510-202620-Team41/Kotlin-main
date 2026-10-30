package com.group41.kotlinapp.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.App
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.repository.ActivityRepository
import com.group41.kotlinapp.repository.JoinResult
import com.group41.kotlinapp.repository.PendingActionRepository
import com.group41.kotlinapp.repository.PreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel responsible for fetching, filtering, and joining recommended activities.
 * Extends AndroidViewModel to access the Application context and obtain Room database DAOs.
 */
class RecommendationsViewModel(application: Application) : AndroidViewModel(application) {

    private val activityRepository = ActivityRepository(
        activityDao = (application as App).database.activityDao(),
        // El buzon de acciones pendientes: sin el, tocar "Unirme" sin internet fallaria
        // con un error en vez de guardarse para enviarlo despues.
        pendingActions = PendingActionRepository(
            (application as App).database.pendingActionDao()
        )
    )
    private val preferencesRepository = PreferencesRepository(
        preferencesDao = (application as App).database.preferencesDao()
    )

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    /**
     * Reads user preferences from Room database for the given userId and returns selected categories to UI.
     */
    fun loadPreferences(
        userId: String,
        onResult: (Set<String>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val preferences = preferencesRepository.getPreferences(userId)
                withContext(Dispatchers.Main) {
                    onResult(preferences.preferredCategories)
                }
            } catch (e: Exception) {
                Log.e("RecommendationsViewModel", "Error loading preferences for userId=$userId", e)
                withContext(Dispatchers.Main) {
                    onResult(emptySet())
                }
            }
        }
    }

    private var recommendationsJob: Job? = null

    fun loadRecommendedActivities(
        userId: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 10.0,
        onSuccess: (List<Activity>) -> Unit,
        onError: (String) -> Unit
    ) {
        recommendationsJob?.cancel()
        recommendationsJob = viewModelScope.launch {
            try {
                val online = try {
                    ApiClient.isOnline()
                } catch (_: Exception) {
                    false
                }
                _isOffline.value = !online

                val activities = activityRepository.getRecommendedActivities(
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm
                )

                val preferences = preferencesRepository.getPreferences(userId)

                val orderedActivities =
                    if (preferences.preferredCategories.isEmpty()) {
                        activities
                    } else {
                        activities.sortedByDescending { activity ->
                            activity.category in preferences.preferredCategories
                        }
                    }

                withContext(Dispatchers.Main) {
                    onSuccess(orderedActivities)
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Error al cargar recomendaciones")
                }
            }
        }
    }

    fun getActivityById(
        activityId: String,
        latitude: Double? = null,
        longitude: Double? = null,
        onResult: (Activity?) -> Unit
    ) {
        viewModelScope.launch {
            val act = activityRepository.getActivityById(activityId, latitude, longitude)
            withContext(Dispatchers.Main) {
                onResult(act)
            }
        }
    }

    fun updatePreferences(
        userId: String,
        categories: Set<String>,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                preferencesRepository.updatePreferences(userId, categories)
                withContext(Dispatchers.Main) {
                    onSuccess?.invoke()
                }
            } catch (e: Exception) {
                Log.e("RecommendationsViewModel", "Error saving preferences to Room for userId=$userId", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke(e.message ?: "Error al guardar preferencias")
                }
            }
        }
    }

    /**
     * Inscribe al usuario, funcione o no el internet.
     *
     * @param onSuccess recibe true cuando la inscripcion quedo GUARDADA esperando conexion,
     *        y false cuando el backend ya la confirmo. La tarjeta usa ese dato para decir
     *        "Pendiente" en vez de "Unido", porque prometer un cupo que el servidor no ha
     *        confirmado es lo unico que el usuario no nos perdonaria.
     * @param onError solo se llama cuando el backend RECHAZO la inscripcion. Los fallos de
     *        red ya no llegan aqui: esos los guarda el repositorio en la cola offline.
     */
    fun joinActivity(
        activity: Activity,
        onSuccess: (queued: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val result = activityRepository.joinActivity(
                    activityId = activity.id,
                    recommendationId = activity.recommendationId,
                    freeTimeMinutes = activity.freeTimeMinutes
                )
                withContext(Dispatchers.Main) {
                    onSuccess(result == JoinResult.QUEUED)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Error al unirse a la actividad")
                }
            }
        }
    }
}