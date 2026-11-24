package com.group41.kotlinapp.data.local.entity

import androidx.room.Entity

/**
 * Persists an enrollment across app restarts.
 *
 * The primary key includes both IDs so accounts can have different enrollment states.
 * @property joined The membership shown immediately after a join or cancellation.
 * @property cancellationPending Whether the cancellation still needs to sync.
 * @property confirmedJoined The state restored if the server rejects the change.
 */
@Entity(tableName = "user_enrollments", primaryKeys = ["userId", "activityId"])
data class UserEnrollmentEntity(
    val userId: String,
    val activityId: String,
    val joined: Boolean,
    val cancellationPending: Boolean = false,
    val confirmedJoined: Boolean = joined
)
