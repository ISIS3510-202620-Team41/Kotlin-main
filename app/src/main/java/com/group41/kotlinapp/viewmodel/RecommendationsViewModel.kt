package com.group41.kotlinapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.App
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.repository.ActivityRepository
import com.group41.kotlinapp.repository.PendingActionRepository
import com.group41.kotlinapp.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel responsible for fetching, filtering, and joining recommended activities.
 * Extends AndroidViewModel to access the Application context and obtain the Room database instance.
 */
class RecommendationsViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Initializes ActivityRepository by supplying ActivityDao from the App database singleton.
     * Casts the Application instance to the app's custom App class to access the shared Room database.
     */
    private val activityRepository = ActivityRepository(
        activityDao = (application as App).database.activityDao(),
        // El buzon de acciones pendientes: sin el, tocar "Unirme" sin internet fallaria
        // con un error en vez de guardarse para enviarlo despues.
        pendingActions = PendingActionRepository(
            (application as App).database.pendingActionDao()
        )
    )
    private val preferencesRepository = PreferencesRepository()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    fun loadRecommendedActivities(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 10.0,
        onSuccess: (List<Activity>) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                // Evaluates device network reachability safely; defaults to false if connectivity check fails
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

                val preferences = preferencesRepository.getPreferences()

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

    fun updatePreferences(categories: Set<String>) {
        preferencesRepository.updatePreferences(categories)
    }

    fun joinActivity(
        activity: Activity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                activityRepository.joinActivity(
                    activityId = activity.id,
                    recommendationId = activity.recommendationId,
                    freeTimeMinutes = activity.freeTimeMinutes
                )
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Error al unirse a la actividad")
                }
            }
        }
    }
}