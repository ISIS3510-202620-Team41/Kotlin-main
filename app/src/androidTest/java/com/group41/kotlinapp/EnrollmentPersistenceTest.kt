package com.group41.kotlinapp.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group41.kotlinapp.data.local.entity.PendingActionEntity
import com.group41.kotlinapp.data.local.entity.UserPreferenceEntity
import com.group41.kotlinapp.data.local.entity.UserEnrollmentEntity
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.model.JoinPayload
import com.group41.kotlinapp.model.PendingActionType
import com.group41.kotlinapp.network.ApiService
import com.group41.kotlinapp.network.EnrollmentAccount
import com.group41.kotlinapp.repository.ActivityRepository
import com.group41.kotlinapp.repository.JoinResult
import com.group41.kotlinapp.repository.PendingActionRepository
import retrofit2.HttpException
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
        .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
                assertNull(db.userEnrollmentDao().get("user", join.activityId)!!.canceledAt)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test
    fun v3MigrationPreservesMembershipWithoutInventingHistory() = runBlocking {
        try {
            val cancellation = PendingActionEntity(
                "CANCEL_ENROLLMENT:user:pending", "user", PendingActionType.CANCEL_ENROLLMENT.name,
                """{"activityId":"pending"}""", 1L
            )
            withDatabase { db ->
                db.pendingActionDao().insertAction(cancellation)
                db.openHelper.writableDatabase.apply {
                    execSQL("DROP TABLE user_enrollments")
                    execSQL(
                        """
                        CREATE TABLE user_enrollments (
                            userId TEXT NOT NULL, activityId TEXT NOT NULL, joined INTEGER NOT NULL,
                            cancellationPending INTEGER NOT NULL, confirmedJoined INTEGER NOT NULL,
                            PRIMARY KEY (userId, activityId)
                        )
                        """.trimIndent()
                    )
                    execSQL("INSERT INTO user_enrollments VALUES ('user', 'active', 1, 0, 1)")
                    execSQL("INSERT INTO user_enrollments VALUES ('user', 'pending', 0, 1, 1)")
                    execSQL("INSERT INTO user_enrollments VALUES ('user', 'unknown', 0, 0, 0)")
                    version = 3
                }
            }
            withDatabase { db ->
                val rows = db.userEnrollmentDao().getForUser("user").associateBy { it.activityId }
                assertEquals(3, rows.size)
                assertTrue(rows.getValue("active").joined)
                assertTrue(rows.getValue("pending").cancellationPending)
                assertTrue(rows.values.all { it.enrolledAt == null && it.canceledAt == null })
                assertEquals(listOf(cancellation), db.pendingActionDao().getPendingActions("user"))
                assertEquals(
                    setOf("active", "pending"),
                    db.userEnrollmentDao().observeEnrolledActivities("user").first()
                        .map { it.enrollmentActivityId }.toSet()
                )
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun confirmedHistorySurvivesReopeningAndMembershipRefresh() = runBlocking {
        try {
            var leaveStatus = 403
            val api = Proxy.newProxyInstance(
                ApiService::class.java.classLoader, arrayOf(ApiService::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "join" -> JsonObject()
                    "leave" -> if (leaveStatus == 204) Response.success<Unit>(204, null)
                        else Response.error<Unit>(leaveStatus, "leave response".toResponseBody())
                    else -> error("Unexpected request: ${method.name}")
                }
            } as ApiService
            val user = "user"
            withDatabase { db ->
                val queue = PendingActionRepository(db) { api }
                assertEquals(JoinResult.SYNCED, queue.changeEnrollment(user, join, true, true, { api }, { true }, {}))
                val confirmed = checkNotNull(queue.enrollment(user, join.activityId))
                assertNotNull(confirmed.enrolledAt)
                try {
                    queue.changeEnrollment(user, join, false, true, { api }, { true }, {})
                    fail("A rejected cancellation should report an error")
                } catch (error: HttpException) {
                    assertEquals(403, error.code())
                    assertEquals(confirmed, queue.enrollment(user, join.activityId))
                }
                assertEquals(JoinResult.QUEUED, queue.changeEnrollment(user, join, false, false, { api }, { true }, {}))
                assertNull(queue.enrollment(user, join.activityId)?.canceledAt)
            }
            withDatabase { db ->
                val queue = PendingActionRepository(db) { api }
                leaveStatus = 204
                assertTrue(queue.syncEnrollment(queue.pendingFor(user).single()) { true })
                val canceled = checkNotNull(queue.enrollment(user, join.activityId))
                assertNotNull(canceled.enrolledAt)
                assertNotNull(canceled.canceledAt)
                assertTrue(queue.pendingFor(user).isEmpty())
            }
            withDatabase { db ->
                val queue = PendingActionRepository(db) { api }
                val canceled = checkNotNull(queue.enrollment(user, join.activityId))
                queue.rememberMembership(user, join.activityId, joined = false) { true }
                assertEquals(canceled, queue.enrollment(user, join.activityId))
                assertTrue(db.userEnrollmentDao().observeEnrolledActivities(user).first().single().isCanceled)
                assertTrue(db.userEnrollmentDao().observeEnrolledActivities("other").first().isEmpty())

                leaveStatus = 404
                assertEquals(JoinResult.SYNCED, queue.changeEnrollment(user, join, false, true, { api }, { true }, {}))
                assertEquals(canceled, queue.enrollment(user, join.activityId))
                assertEquals(JoinResult.SYNCED, queue.changeEnrollment(user, join, true, true, { api }, { true }, {}))
                val rejoined = db.userEnrollmentDao().observeEnrolledActivities(user).first().single()
                assertFalse(rejoined.isCanceled)
                assertEquals(canceled.canceledAt, rejoined.canceledAt)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun activityReadsPreserveHistoryWhenMembershipIsUnknown() = runBlocking {
        val confirmed = UserEnrollmentEntity("user", "active", joined = true, enrolledAt = 10L)
        val canceled = UserEnrollmentEntity("user", "history", joined = false, enrolledAt = 20L, canceledAt = 30L)
        val activePayload = activityResponse(confirmed.activityId)
        val canceledPayload = activityResponse(canceled.activityId)
        val negative = activityResponse("never-joined").apply { addProperty("joined", false) }

        try {
            withDatabase { db ->
                db.userEnrollmentDao().upsert(confirmed)
                db.userEnrollmentDao().upsert(canceled)
            }
            withDatabase { db ->
                val api = membershipApi(listOf(activePayload, canceledPayload, negative)) { _, _ -> }
                val repository = membershipRepository(db, api) { "user" }
                repository.getRecommendedActivities(1.0, 1.0)
                for (unknown in listOf(JsonNull.INSTANCE, JsonPrimitive("false"), JsonPrimitive(0))) {
                    activePayload.add("joined", unknown)
                    canceledPayload.add("joined", unknown)
                    repository.getNearbyActivities(1.0, 1.0)
                    repository.getActivityById(confirmed.activityId)
                    repository.getActivityById(canceled.activityId)
                    assertEquals(
                        setOf(confirmed, canceled),
                        db.userEnrollmentDao().getForUser("user").toSet()
                    )
                }
                activePayload.addProperty("joined", false)
                repository.getActivityById(confirmed.activityId)
                val updated = checkNotNull(db.userEnrollmentDao().get("user", confirmed.activityId))
                assertFalse(updated.joined)
                assertFalse(updated.confirmedJoined)
                assertEquals(confirmed.enrolledAt, updated.enrolledAt)
                assertNotNull(updated.canceledAt)
                assertEquals(canceled, db.userEnrollmentDao().get("user", canceled.activityId))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun activityReadsBindTheRequestAccountAndDropMembershipAfterSwitchingAccounts() = runBlocking {
        val first = UserEnrollmentEntity("first", "shared", joined = true, enrolledAt = 10L)
        val second = first.copy(userId = "second", enrolledAt = 20L)
        var currentUser = first.userId
        val requestedMethods = mutableListOf<String>()
        val negative = activityResponse(first.activityId).apply { addProperty("joined", false) }
        val api = membershipApi(listOf(negative)) { method, transmittedAccount ->
            assertEquals(EnrollmentAccount(first.userId), transmittedAccount)
            requestedMethods.add(method)
            // Switch accounts after request dispatch and before the response can reconcile Room.
            currentUser = second.userId
        }
        try {
            withDatabase { db ->
                db.userEnrollmentDao().upsert(first)
                db.userEnrollmentDao().upsert(second)
                val repository = membershipRepository(db, api) { currentUser }
                repository.getRecommendedActivities(1.0, 1.0)
                currentUser = first.userId
                repository.getNearbyActivities(1.0, 1.0)
                currentUser = first.userId
                repository.getActivityById(first.activityId)

                assertEquals(listOf("recommendations", "nearbyActivities", "getActivity"), requestedMethods)
                assertEquals(first, db.userEnrollmentDao().get(first.userId, first.activityId))
                assertEquals(second, db.userEnrollmentDao().get(second.userId, second.activityId))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun membershipRepository(db: AppDatabase, api: ApiService, userId: () -> String?) =
        ActivityRepository(db.activityDao(), PendingActionRepository(db) { api }, { api }, { true }, userId)

    private fun activityResponse(id: String) = JsonObject().apply {
        addProperty("id", id)
        addProperty("title", id)
        addProperty("category", "DEPORTES")
    }

    private fun membershipApi(
        activities: List<JsonObject>,
        onRequest: (String, Any?) -> Unit
    ): ApiService = Proxy.newProxyInstance(
        ApiService::class.java.classLoader, arrayOf(ApiService::class.java)
    ) { _, method, args ->
        // The last JVM argument is the continuation for the Retrofit suspend method.
        onRequest(method.name, args[args.lastIndex - 1])
        when (method.name) {
            "getActivity" -> Response.success(activities.single { it.get("id").asString == args[0] })
            "nearbyActivities" -> Response.success(JsonArray().apply { activities.forEach { add(it) } })
            "recommendations" -> Response.success(JsonArray().apply {
                activities.forEach { activity -> add(JsonObject().apply { add("activity", activity) }) }
            })
            else -> error("Unexpected request: ${method.name}")
        }
    } as ApiService
}
