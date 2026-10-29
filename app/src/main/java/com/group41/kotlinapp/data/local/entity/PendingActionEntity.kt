package com.group41.kotlinapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing the 'pending_actions' table in Room.
 * Stores offline user actions so they can be replayed to the backend when connection is restored.
 */
@Entity(tableName = "pending_actions")
data class PendingActionEntity(
    /**
     * @PrimaryKey uniquely identifies each row in the SQLite table.
     * It ensures no two pending actions share the same identifier and allows Room
     * to perform fast lookups, update specific records (like updating retryCount),
     * or delete individual actions once they successfully sync with the server.
     */
    @PrimaryKey
    val id: String,

    // Identifies the user who performed the action
    val userId: String,

    // Type of action to replay (e.g., "CREATE_ACTIVITY", "JOIN_ACTIVITY")
    val actionType: String,

    // Serialized JSON payload containing the necessary data to execute the action
    val payload: String,

    // Epoch timestamp (milliseconds) to ensure actions are replayed in exact chronological order
    val createdAt: Long,

    // Counter tracking failed synchronization attempts for exponential backoff/retry handling
    val retryCount: Int = 0
)