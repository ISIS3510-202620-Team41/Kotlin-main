package com.group41.kotlinapp.model

data class Activity(
    val id: String,
    val name: String,
    val category: String,
    val durationMinutes: Int,
    val distanceMeters: Int,
    val score: Double = 0.0,
    val joined: Boolean = false,
    val recommendationId: String? = null,
    val freeTimeMinutes: Int? = null
)