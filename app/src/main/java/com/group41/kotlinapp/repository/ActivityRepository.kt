package com.group41.kotlinapp.repository

import com.group41.kotlinapp.model.Activity
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class ActivityRepository {

    private val baseUrl = "http://10.0.2.2:8080"

    fun getRecommendedActivities(
        accessToken: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 5.0
    ): List<Activity> {

        val url = URL(
            "$baseUrl/api/activities/recommendations" +
                    "?lat=$latitude&lon=$longitude&radius=$radiusKm"
        )

        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty(
                "Authorization",
                "Bearer $accessToken"
            )
            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception(
                    "Backend returned HTTP ${connection.responseCode}"
                )
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            return parseRecommendations(response)

        } finally {
            connection.disconnect()
        }
    }

    private fun parseRecommendations(json: String): List<Activity> {
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
                    score = recommendation.getDouble("score")
                )
            )
        }

        return activities
    }

    private fun formatCategory(category: String): String {
        return when (category) {
            "DEPORTES" -> "Deporte"
            "ESTUDIO" -> "Estudio"
            "CULTURA" -> "Cultural"
            "ENTRETENIMIENTO" -> "Entretenimiento"
            else -> category
        }
    }

    fun getNearbyActivities(
        accessToken: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 1.0
    ): List<Activity> {

        val url = URL(
            "$baseUrl/api/activities" +
                    "?lat=$latitude&lon=$longitude&radius=$radiusKm"
        )

        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"

            connection.setRequestProperty(
                "Authorization",
                "Bearer $accessToken"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception(
                    "Backend returned HTTP ${connection.responseCode}"
                )
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            return parseNearbyActivities(response)

        } finally {
            connection.disconnect()
        }
    }

    private fun parseNearbyActivities(json: String): List<Activity> {
        val array = JSONArray(json)
        val activities = mutableListOf<Activity>()

        for (i in 0 until array.length()) {
            val activityJson = array.getJSONObject(i)

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
                    joined = activityJson.optBoolean("joined", false)
                )
            )
        }

        return activities
    }


}