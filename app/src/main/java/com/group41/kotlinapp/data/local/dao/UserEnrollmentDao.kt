package com.group41.kotlinapp.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.group41.kotlinapp.data.local.entity.UserEnrollmentEntity

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
