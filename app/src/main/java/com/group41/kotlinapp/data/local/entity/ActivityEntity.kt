package com.group41.kotlinapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity class representing the 'activities' table in the local Room database.
 * This acts as the offline cache for the Activity domain model.
 */
@Entity(tableName = "activities")
data class ActivityEntity(
    // Unique identifier for the activity, acts as the primary key in the database
    @PrimaryKey
    val id: String,

    // Core details preserving strict non-null types from the domain model
    val name: String,
    val category: String,
    val durationMinutes: Int,

    /** Geographical data: distanceMeters is saved as a cached value.
    *For accurate offline distance, recalculate using latitude/longitude
    *against the user's updated device location.
    */
    val distanceMeters: Int,
    val latitude: Double,
    val longitude: Double,

    val score: Double,
    val joined: Boolean,

    // Optional context values allowing nulls just like Activity.kt
    val recommendationId: String?,
    val freeTimeMinutes: Int?,

    val price: String,

    // Capacity remains Int? to handle activities with unlimited/undefined capacity
    val capacity: Int?,
    val participants: Int,

    val aboutLocation: String?,
    val locationName: String?,
    /**
     * Stores only the URL string. Actual offline image availability requires
     * a separate local file caching mechanism (like Coil/Glide offline cache).
     */

    val imageUrl: String?,

    val description: String?,
    val startTime: String?,
    val endTime: String?
)