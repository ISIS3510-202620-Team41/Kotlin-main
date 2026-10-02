package com.group41.kotlinapp.analytics

import java.net.HttpURLConnection
import java.net.URL

object AnalyticsClient {

    private const val BASE_URL = "http://10.0.2.2:8000"

    fun sendEvent(event: AnalyticsEvent) {
        Thread {
            try {
                val url = URL("$BASE_URL/events")
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )
                connection.doOutput = true

                val json = """
                    {
                        "eventType": "${event.eventType}",
                        "activityId": "${event.activityId}",
                        "category": "${event.category}",
                        "freeTimeMinutes": ${event.freeTimeMinutes},
                        "timestamp": ${event.timestamp?.let { "\"$it\"" } ?: "null"}
                    }
                """.trimIndent()

                connection.outputStream.use { output ->
                    output.write(json.toByteArray())
                }

                connection.responseCode

                connection.disconnect()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}