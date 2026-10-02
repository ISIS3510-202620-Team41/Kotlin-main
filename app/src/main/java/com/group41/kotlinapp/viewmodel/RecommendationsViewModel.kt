package com.group41.kotlinapp.viewmodel

import androidx.lifecycle.ViewModel
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.repository.ActivityRepository
import com.group41.kotlinapp.repository.PreferencesRepository

class RecommendationsViewModel : ViewModel() {

    private val activityRepository = ActivityRepository()
    private val preferencesRepository = PreferencesRepository()

    fun getRecommendedActivities(): List<Activity> {
        val activities = activityRepository.getActivities()
        val preferences = preferencesRepository.getPreferences()

        return activities.sortedByDescending { activity ->
            activity.category in preferences.preferredCategories
        }
    }

    fun updatePreferences(categories: Set<String>) {
        preferencesRepository.updatePreferences(categories)
    }
}