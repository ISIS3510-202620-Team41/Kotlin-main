package com.group41.kotlinapp.data.local.entity

import androidx.room.Entity

/**
 * Entity representing the 'user_preferences' table in the local Room database.
 * Stores preferred categories associated with a user.
 */
@Entity(
    tableName = "user_preferences",
    primaryKeys = ["userId", "category"]
)
data class UserPreferenceEntity(
    val userId: String,
    val category: String
)