package com.group41.kotlinapp.data.local.entity

import androidx.room.Entity

/**
 * Persists an enrollment across app restarts.
 *
 * The primary key includes both IDs so accounts can have different enrollment states.
 * @property joined The membership shown immediately after a join or cancellation.
 * @property cancellationPending Whether the cancellation still needs to sync.
 * @property confirmedJoined The state restored if the server rejects the change.
 * @property enrolledAt Epoch milliseconds when the latest confirmed enrollment became known.
 * @property canceledAt Epoch milliseconds when the latest confirmed cancellation became known.
 * Both timestamps may be absent for records carried forward from older database versions.
 */
@Entity(tableName = "user_enrollments", primaryKeys = ["userId", "activityId"])
data class UserEnrollmentEntity(
    val userId: String,
    val activityId: String,
    val joined: Boolean,
    val cancellationPending: Boolean = false,
    val confirmedJoined: Boolean = joined,
    val enrolledAt: Long? = null,
    val canceledAt: Long? = null
)
