package com.group41.kotlinapp.network

data class CreateActivityRequest(
    val title: String,
    val category: String,
    val startTime: String,
    val endTime: String,
    val locationName: String,
    val latitude: Double,
    val longitude: Double,
    val description: String? = null,
    val imageUrl: String? = null,
    val price: String? = null,
    val capacity: Int? = null,
    val aboutLocation: String? = null
)
