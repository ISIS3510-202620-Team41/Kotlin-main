package com.group41.kotlinapp.data.local

import com.group41.kotlinapp.data.local.entity.ActivityEntity
import com.group41.kotlinapp.model.Activity

/**
 * Mapper functions to convert between the Activity domain model used by the UI/Network layers
 * and the ActivityEntity model stored in the Room local database.
 */

/**
 * Converts an Activity domain model instance into an ActivityEntity suitable for Room persistence.
 * Maps every field 1-to-1 to ensure no optional details or metrics are lost when caching offline.
 */
fun Activity.toEntity(): ActivityEntity {
    return ActivityEntity(
        id = id,
        name = name,
        category = category,
        durationMinutes = durationMinutes,
        distanceMeters = distanceMeters,
        latitude = latitude,
        longitude = longitude,
        score = score,
        joined = joined,
        recommendationId = recommendationId,
        freeTimeMinutes = freeTimeMinutes,
        price = price,
        capacity = capacity,
        participants = participants,
        aboutLocation = aboutLocation,
        locationName = locationName,
        imageUrl = imageUrl,
        description = description,
        startTime = startTime,
        endTime = endTime
    )
}

/**
 * Converts an ActivityEntity loaded from Room back into an Activity domain model.
 * Allows ViewModels and screens to consume database records seamlessly without altering UI logic.
 */
fun ActivityEntity.toActivity(): Activity {
    return Activity(
        id = id,
        name = name,
        category = category,
        durationMinutes = durationMinutes,
        distanceMeters = distanceMeters,
        score = score,
        joined = joined,
        recommendationId = recommendationId,
        freeTimeMinutes = freeTimeMinutes,
        price = price,
        capacity = capacity,
        participants = participants,
        aboutLocation = aboutLocation,
        locationName = locationName,
        latitude = latitude,
        longitude = longitude,
        imageUrl = imageUrl,
        description = description,
        startTime = startTime,
        endTime = endTime
    )
}