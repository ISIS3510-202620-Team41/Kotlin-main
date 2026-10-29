package com.group41.kotlinapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.group41.kotlinapp.App
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.repository.ActivityRepository
import kotlinx.coroutines.launch

/**
 * ViewModel responsible for fetching and providing nearby activities to the UI.
 * Extends AndroidViewModel to access the Application context and obtain the Room database instance.
 */
class NearbyActivitiesViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Initializes ActivityRepository by obtaining the ActivityDao from the App database singleton.
     * Casts the Application instance to the app's custom App class to access the shared Room database.
     */
    private val activityRepository = ActivityRepository(
        activityDao = (application as App).database.activityDao()
    )

    /**
     * Asynchronously loads nearby activities within a specified radius.
     * Executes within viewModelScope and delegates to ActivityRepository,
     * which uses Room disk storage as an offline fallback.
     * viewModelScope is an extension property provided by Android's KTX library that binds
     * Kotlin coroutines directly to the lifecycle of a ViewModel
     */
    fun loadNearbyActivities(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 1.0,
        onSuccess: (List<Activity>) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val activities = activityRepository.getNearbyActivities(
                    latitude = latitude,
                    longitude = longitude,
                    radiusKm = radiusKm
                )

                onSuccess(activities)

            } catch (e: Exception) {
                onError(e.message ?: "Unknown error")
            }
        }
    }
}