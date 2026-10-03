package com.group41.kotlinapp.repository

import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.RecommendationsPage
import org.json.JSONArray
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class ActivityRepository {

    suspend fun getRecommendedActivities(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 10.0
    ): List<Activity> {

        val response = ApiClient.api.recommendations(
            lat = latitude,
            lon = longitude,
            radiusKm = radiusKm
        )

        if (!response.isSuccessful) {
            throw Exception("Backend returned HTTP ${response.code()}")
        }

        val page = RecommendationsPage.from(response)

        return parseRecommendations(
            json = page.items.toString(),
            recommendationId = page.recommendationId,
            freeTimeMinutes = page.freeTimeMinutes
        )
    }

    suspend fun joinActivity(
        activityId: String,
        recommendationId: String?,
        freeTimeMinutes: Int?
    ) {
        ApiClient.api.join(
            activityId,
            recommendationId,
            freeTimeMinutes
        )
    }

    private fun parseRecommendations(
        json: String,
        recommendationId: String?,
        freeTimeMinutes: Int?
    ): List<Activity> {

        val array = JSONArray(json)
        val activities = mutableListOf<Activity>()

        for (i in 0 until array.length()) {

            val recommendation = array.getJSONObject(i)
            val activityJson = recommendation.getJSONObject("activity")

            val start = OffsetDateTime.parse(
                activityJson.getString("startTime")
            )

            val end = OffsetDateTime.parse(
                activityJson.getString("endTime")
            )

            val durationMinutes = ChronoUnit.MINUTES.between(
                start,
                end
            ).toInt()

            val distanceKm =
                if (activityJson.isNull("distanceKm")) {
                    0.0
                } else {
                    activityJson.getDouble("distanceKm")
                }

            activities.add(
                Activity(
                    id = activityJson.getString("id"),
                    name = activityJson.getString("title"),
                    category = formatCategory(
                        activityJson.getString("category")
                    ),
                    durationMinutes = durationMinutes,
                    distanceMeters = (distanceKm * 1000).toInt(),
                    score = recommendation.optDouble("score", 0.0),
                    joined = activityJson.optBoolean("joined", false),
                    recommendationId = recommendationId,
                    freeTimeMinutes = freeTimeMinutes
                )
            )
        }

        return activities
    }

    private fun formatCategory(category: String): String {
        return when (category.uppercase()) {
            "FOOD" -> "Comida"
            "GAME" -> "Juego"
            "MUSIC" -> "Música"
            "STUDY" -> "Estudio"
            "CRAFTS" -> "Manualidades"
            "SPORT" -> "Deporte"
            "CULTURE" -> "Cultural"
            "REST" -> "Descanso"
            "ENTERTAINMENT" -> "Entretenimiento"
            else -> category
        }
    }
}