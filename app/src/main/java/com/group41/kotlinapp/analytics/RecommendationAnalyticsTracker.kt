package com.group41.kotlinapp.analytics

object RecommendationAnalyticsTracker {

    fun trackRecommendationShown(
        activityId: String,
        category: String,
        freeTimeMinutes: Int
    ) {
        AnalyticsClient.sendEvent(
            AnalyticsEvent(
                eventType = "recommendation_shown",
                activityId = activityId,
                category = category,
                freeTimeMinutes = freeTimeMinutes
            )
        )
    }

    fun trackRecommendationSelected(
        activityId: String,
        category: String,
        freeTimeMinutes: Int
    ) {
        AnalyticsClient.sendEvent(
            AnalyticsEvent(
                eventType = "recommended_activity_selected",
                activityId = activityId,
                category = category,
                freeTimeMinutes = freeTimeMinutes
            )
        )
    }
}