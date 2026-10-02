package com.group41.kotlinapp.analytics

data class AnalyticsEvent(
    val eventType: String,
    val activityId: String,
    val category: String,
    val freeTimeMinutes: Int,
    val timestamp: String? = null
)