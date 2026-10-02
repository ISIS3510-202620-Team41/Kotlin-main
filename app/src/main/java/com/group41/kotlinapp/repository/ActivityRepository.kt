package com.group41.kotlinapp.repository

import com.group41.kotlinapp.model.Activity

class ActivityRepository {

    private val activities = listOf(
        Activity(
            name = "Bistro",
            category = "Comida",
            durationMinutes = 30,
            distanceMeters = 500
        ),
        Activity(
            name = "Pizza Hut",
            category = "Comida",
            durationMinutes = 45,
            distanceMeters = 700
        ),
        Activity(
            name = "Sala de estudio",
            category = "Estudio",
            durationMinutes = 60,
            distanceMeters = 300
        ),
        Activity(
            name = "Partido de fútbol",
            category = "Deporte",
            durationMinutes = 60,
            distanceMeters = 900
        ),
        Activity(
            name = "Visita cultural",
            category = "Cultural",
            durationMinutes = 90,
            distanceMeters = 1200
        ),
        Activity(
            name = "Descanso en el parque",
            category = "Descanso",
            durationMinutes = 30,
            distanceMeters = 400
        )
    )

    fun getActivities(): List<Activity> {
        return activities
    }
}