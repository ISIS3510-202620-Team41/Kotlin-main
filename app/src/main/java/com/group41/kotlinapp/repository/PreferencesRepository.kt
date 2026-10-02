package com.group41.kotlinapp.repository

import com.group41.kotlinapp.model.UserPreferences

class PreferencesRepository {

    private var userPreferences = UserPreferences(
        preferredCategories = emptySet()
    )

    fun getPreferences(): UserPreferences {
        return userPreferences
    }

    fun updatePreferences(categories: Set<String>) {
        userPreferences = UserPreferences(
            preferredCategories = categories
        )
    }
}