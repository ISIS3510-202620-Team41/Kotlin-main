package com.group41.kotlinapp

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group41.kotlinapp.data.local.AppDatabase
import com.group41.kotlinapp.data.local.dao.EnrolledActivity
import com.group41.kotlinapp.data.local.dao.UserEnrollmentDao
import com.group41.kotlinapp.data.local.entity.UserEnrollmentEntity
import com.group41.kotlinapp.data.local.toEntity
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.model.JoinPayload
import com.group41.kotlinapp.network.ApiService
import com.group41.kotlinapp.repository.PendingActionRepository
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class MyActivitiesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun showsLocalEnrollmentsAndUpdatesAfterOnlineAndOfflineCancellation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = "my-activities-${UUID.randomUUID()}.db"
        fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, filename).build()

        val requests = AtomicInteger()
        val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader, arrayOf(ApiService::class.java)
        ) { _, method, _ ->
            check(method.name == "leave")
            requests.incrementAndGet()
            Response.success<Unit>(204, null)
        } as ApiService
        val online = mutableStateOf(true)
        val database = mutableStateOf<AppDatabase?>(openDatabase())

        try {
            val initial = checkNotNull(database.value)
            runBlocking {
                initial.addEnrollment("online", "Actividad en línea", "user")
                initial.addEnrollment("other", "Actividad de otra cuenta", "other-user")
            }
            compose.setContent {
                database.value?.let { db ->
                    MyActivitiesScreen(
                        userId = "user",
                        enrollmentDao = db.userEnrollmentDao(),
                        isOnline = online.value,
                        onOpenActivity = {},
                        cancelEnrollment = { id ->
                            PendingActionRepository(db) { api }.changeEnrollment(
                                "user", JoinPayload(id, null, null), false, online.value,
                                { api }, { true }, {}
                            )
                        },
                        loadActivity = {}
                    )
                }
            }

            awaitText("Inscripción confirmada")
            compose.onNodeWithText("Actividad en línea").assertIsDisplayed()
            compose.onNodeWithText("Actividad de otra cuenta").assertDoesNotExist()
            compose.onNodeWithText("Cancelar inscripción").performClick()
            awaitText("No tienes inscripciones activas.")
            compose.onNodeWithText("Historial").assertIsDisplayed()
            compose.onNodeWithText("Inscripción cancelada").assertIsDisplayed()
            compose.onNodeWithText("Actividad en línea").assertIsDisplayed()
            compose.onNodeWithText("Cancelar inscripción").assertDoesNotExist()
            assertEquals(1, requests.get())

            compose.runOnIdle { online.value = false }
            runBlocking { initial.addEnrollment("offline", "Actividad sin conexión", "user") }
            awaitText("Actividad sin conexión")
            compose.onNodeWithText("Cancelar inscripción").performClick()
            awaitText("Cancelación pendiente")
            assertEquals(1, requests.get())

            runBlocking {
                initial.activityDao().insertActivity(Activity("join", "Actividad por confirmar", "Deportes", 30, 0).toEntity())
                PendingActionRepository(initial) { api }.changeEnrollment(
                    "user", JoinPayload("join", null, null), true, false, { api }, { true }, {}
                )
            }
            awaitText("Inscripción pendiente")

            // Dispose the screen and close Room before reopening the same on-disk database.
            compose.runOnIdle { database.value = null }
            compose.waitForIdle()
            initial.close()
            val reopened = openDatabase()
            compose.runOnIdle { database.value = reopened }
            awaitText("Cancelación pendiente")
            awaitText("Inscripción pendiente")
            val restored = runBlocking { reopened.userEnrollmentDao().observeEnrolledActivities("user").first() }
            assertTrue(restored.single { it.enrollmentActivityId == "online" }.isCanceled)
            assertTrue(restored.single { it.enrollmentActivityId == "offline" }.cancellationPending)

            compose.runOnIdle { online.value = true }
            // Replay the saved action through the queue's sync path. The screen should
            // update through its Room subscription without a manual refresh.
            runBlocking {
                val queue = PendingActionRepository(reopened) { api }
                val cancellation = queue.pendingFor("user").single { it.payload.getString("activityId") == "offline" }
                check(queue.syncEnrollment(cancellation) { true })
            }
            awaitText("Inscripción pendiente")
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Cancelación pendiente").fetchSemanticsNodes().isEmpty()
            }
            val synced = runBlocking { reopened.userEnrollmentDao().observeEnrolledActivities("user").first() }
            assertTrue(synced.single { it.enrollmentActivityId == "offline" }.isCanceled)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Actividad sin conexión"))
            compose.onNodeWithText("Actividad sin conexión").assertIsDisplayed()
            assertEquals(2, requests.get())
        } finally {
            database.value?.close()
            context.deleteDatabase(filename)
        }
    }

    @Test
    fun retriesFailedReadAndLoadsMissingDetailsOnReconnect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val online = mutableStateOf(false)
        val reads = AtomicInteger()
        val roomDao = db.userEnrollmentDao()
        // Fail the first read and delegate subsequent attempts to the real Room DAO.
        val dao = object : UserEnrollmentDao by roomDao {
            override fun observeEnrolledActivities(userId: String): Flow<List<EnrolledActivity>> = flow {
                if (reads.getAndIncrement() == 0) throw IOException("Room read failed")
                emitAll(roomDao.observeEnrolledActivities(userId))
            }
        }

        try {
            runBlocking { roomDao.upsert(UserEnrollmentEntity("user", "missing", joined = true)) }
            compose.setContent {
                MyActivitiesScreen(
                    userId = "user",
                    enrollmentDao = dao,
                    isOnline = online.value,
                    onOpenActivity = {},
                    cancelEnrollment = {},
                    loadActivity = { id ->
                        db.activityDao().insertActivity(Activity(id, "Detalles recuperados", "Deportes", 30, 0).toEntity())
                    }
                )
            }

            awaitText("No se pudieron cargar tus inscripciones.")
            compose.onNodeWithText("Reintentar").performClick()
            awaitText("Detalles no disponibles sin conexión.")
            compose.onNodeWithText("Cancelar inscripción").assertIsDisplayed()

            compose.runOnIdle { online.value = true }
            awaitText("Detalles recuperados")
            compose.onNodeWithText("Inscripción confirmada").assertIsDisplayed()
        } finally {
            db.close()
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private suspend fun AppDatabase.addEnrollment(id: String, name: String, userId: String) {
        activityDao().insertActivity(Activity(id, name, "Deportes", 30, 0).toEntity())
        userEnrollmentDao().upsert(UserEnrollmentEntity(userId, id, joined = true))
    }
}
