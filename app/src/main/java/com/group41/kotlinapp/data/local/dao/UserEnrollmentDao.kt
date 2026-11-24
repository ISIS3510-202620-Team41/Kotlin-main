package com.group41.kotlinapp.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.group41.kotlinapp.data.local.entity.ActivityEntity
import com.group41.kotlinapp.data.local.entity.UserEnrollmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Enrollment and activity details read together for My Activities.
 * The activity can be missing from the local cache while the enrollment is still valid.
 */
data class EnrolledActivity(
    val enrollmentActivityId: String,
    val cancellationPending: Boolean,
    val joinPending: Boolean,
    val enrollmentJoined: Boolean,
    val enrolledAt: Long?,
    val canceledAt: Long?,
    @Embedded val activity: ActivityEntity?
) {
    val isCanceled: Boolean get() = !enrollmentJoined && !cancellationPending && canceledAt != null
}

/**
 * Data Access Object (DAO) for managing user enrollments in the 'user_enrollments' table.
 * Room automatically implements these methods at compile time.
 */
@Dao
interface UserEnrollmentDao {

    /**
     * Inserts an enrollment if its user and activity pair is absent, or updates it if present.
     * @Upsert updates the existing row in place.
     */
    @Upsert
    suspend fun upsert(enrollment: UserEnrollmentEntity)

    /**
     * Retrieves the enrollment for a specific user and activity.
     * Returns null if the pair has no local record.
     */
    @Query("SELECT * FROM user_enrollments WHERE userId = :userId AND activityId = :activityId")
    suspend fun get(userId: String, activityId: String): UserEnrollmentEntity?

    /**
     * Retrieves all local enrollment records belonging to the given user ID.
     */
    @Query("SELECT * FROM user_enrollments WHERE userId = :userId")
    suspend fun getForUser(userId: String): List<UserEnrollmentEntity>

    /**
     * Observes active, pending, and canceled enrollments for one account.
     *
     * The left join preserves enrollments whose activity details are missing from the cache.
     * Completed cancellations remain available using their cancellation timestamp.
     * Pending joins are identified by their entry in the shared offline action queue.
     * Room emits a fresh list when any of the three tables changes during synchronization.
     */
    @Query("""
        SELECT a.*, e.activityId AS enrollmentActivityId, e.cancellationPending,
            e.joined AS enrollmentJoined, e.enrolledAt, e.canceledAt,
            EXISTS (
                SELECT 1 FROM pending_actions AS p
                WHERE p.id = 'JOIN_ACTIVITY:' || e.userId || ':' || e.activityId
                    AND p.userId = e.userId AND p.actionType = 'JOIN_ACTIVITY'
            ) AS joinPending
        FROM user_enrollments AS e
        LEFT JOIN activities AS a ON a.id = e.activityId
        WHERE e.userId = :userId AND (e.joined = 1 OR e.cancellationPending = 1 OR e.canceledAt IS NOT NULL)
        ORDER BY e.cancellationPending DESC, a.startTime IS NULL, a.startTime, a.name, e.activityId
    """)
    fun observeEnrolledActivities(userId: String): Flow<List<EnrolledActivity>>

    /**
     * Checks whether a join or cancellation is queued for the specified user and activity.
     * The result lets the UI show that the local change is still pending synchronization.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM pending_actions WHERE userId = :userId AND id IN (:joinId, :cancelId))")
    suspend fun hasPendingAction(userId: String, joinId: String, cancelId: String): Boolean

    /**
     * Updates an existing enrollment matched by its user ID and activity ID.
     */
    @Update
    suspend fun update(enrollment: UserEnrollmentEntity)

    /**
     * Deletes the local enrollment matching the entity's user ID and activity ID.
     */
    @Delete
    suspend fun delete(enrollment: UserEnrollmentEntity)
}
