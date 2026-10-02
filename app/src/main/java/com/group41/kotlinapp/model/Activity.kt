package com.group41.kotlinapp.model

data class Activity(
    val name: String,
    val category: String,
    val durationMinutes: Int,
    val distanceMeters: Int
)