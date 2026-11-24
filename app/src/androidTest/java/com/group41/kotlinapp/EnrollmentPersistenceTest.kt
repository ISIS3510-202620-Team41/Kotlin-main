package com.group41.kotlinapp.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group41.kotlinapp.data.local.entity.PendingActionEntity
import com.group41.kotlinapp.data.local.entity.UserPreferenceEntity
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.model.JoinPayload
import com.group41.kotlinapp.model.PendingActionType
import com.group41.kotlinapp.network.ApiService
import com.group41.kotlinapp.repository.JoinResult
import com.group41.kotlinapp.repository.PendingActionRepository
import retrofit2.HttpException
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class EnrollmentPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "enrollment-test-${UUID.randomUUID()}.db"
    private val join = JoinPayload("activity", "recommendation", 45)

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(MIGRATION_2_3)
        .build()

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val db = open()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun v2MigrationPreservesExistingRowsAndRestoresPendingJoin() = runBlocking {
        try {
            val activity = Activity("activity", "Football", "Deportes", 60, 0, joined = true, pendingSync = true)
                .toEntity()
            val preference = UserPreferenceEntity("user", "DEPORTES")
            val queued = PendingActionEntity(
                PendingActionRepository.joinActionId("user", join.activityId), "user",
                PendingActionType.JOIN_ACTIVITY.name, join.toJson().toString(), 1L, 2
            )
            withDatabase { db ->
                db.activityDao().insertActivity(activity)
                db.preferencesDao().insertPreferences(listOf(preference))
                db.pendingActionDao().insertAction(queued)
                // The remaining tables are identical to version 2.
                db.openHelper.writableDatabase.apply {
                    execSQL("DROP TABLE user_enrollments")
                    version = 2
                }
            }
            withDatabase { db ->
                assertEquals(activity, db.activityDao().getActivityById(join.activityId))
                assertEquals(listOf(queued), db.pendingActionDao().getPendingActions("user"))
                assertTrue(db.userEnrollmentDao().get("user", join.activityId)!!.joined)
                assertNull(db.userEnrollmentDao().get("other", join.activityId))
                val cursor = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM user_preferences")
                cursor.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test
    // Reopen the database to verify that both the queue and enrollment survive.
    // A 503 simulates a temporary failure. A 404 confirms the enrollment is absent.
    fun latestIntentSurvivesReopenAndAlreadyCancelledResponseClearsPending() = runBlocking {
        try {
            var connected = false
            val api = Proxy.newProxyInstance(
                ApiService::class.java.classLoader, arrayOf(ApiService::class.java)
            ) { _, method, args ->
                check(method.name == "leave" && args[0] == join.activityId)
                if (!connected) throw HttpException(Response.error<Unit>(503, "unavailable".toResponseBody()))
                Response.error<Unit>(404, "absent".toResponseBody())
            } as ApiService
            withDatabase { db ->
                val queue = PendingActionRepository(db) { api }
                suspend fun request(joined: Boolean) =
                    queue.changeEnrollment("user", join, joined, false, { api }, { true }, {})
                assertEquals(JoinResult.QUEUED, request(true))
                assertEquals(JoinResult.QUEUED, request(false))
                assertEquals(JoinResult.QUEUED, request(false))
                assertEquals(PendingActionType.CANCEL_ENROLLMENT, queue.pendingFor("user").single().type)
            }
            withDatabase { db ->
                val queue = PendingActionRepository(db) { api }
                val enrollment = db.userEnrollmentDao().get("user", join.activityId)!!
                assertFalse(enrollment.joined)
                assertTrue(enrollment.cancellationPending)
                val action = queue.pendingFor("user").single()

                try {
                    queue.syncEnrollment(action) { true }
                    fail("A connection failure should request a retry")
                } catch (error: HttpException) {
                    assertEquals(503, error.code())
                    assertTrue(db.userEnrollmentDao().get("user", join.activityId)!!.cancellationPending)
                    assertEquals(1, queue.pendingFor("user").single().retryCount)
                }

                connected = true
                assertTrue(queue.syncEnrollment(action) { true })
                assertTrue(queue.pendingFor("user").isEmpty())
                assertFalse(db.userEnrollmentDao().get("user", join.activityId)!!.cancellationPending)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }
}
