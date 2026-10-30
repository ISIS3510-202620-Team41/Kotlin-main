package com.group41.kotlinapp.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.group41.kotlinapp.data.local.entity.ActivityEntity
import com.group41.kotlinapp.data.local.entity.UserPreferenceEntity
import com.group41.kotlinapp.data.local.entity.PendingActionEntity
import com.group41.kotlinapp.network.ApiService
import com.group41.kotlinapp.network.CreateActivityRequest
import com.group41.kotlinapp.repository.PendingActionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

        database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndRetrieveActivity() = runBlocking {
        val activity = ActivityEntity(
            id = "activity-1",
            name = "Football Match",
            category = "DEPORTES",
            durationMinutes = 90,
            distanceMeters = 500,
            latitude = 4.601,
            longitude = -74.065,
            score = 0.85,
            joined = false,
            recommendationId = null,
            freeTimeMinutes = 120,
            price = "Gratis",
            capacity = 10,
            participants = 4,
            aboutLocation = null,
            locationName = "Universidad de los Andes",
            imageUrl = null,
            description = "Friendly football match",
            startTime = null,
            endTime = null
        )

        database.activityDao().insertActivity(activity)

        val retrieved = database.activityDao()
            .getActivityById("activity-1")

        assertEquals(activity, retrieved)
        assertNull(
            database.activityDao().getActivityById("nonexistent")
        )
    }

    @Test
    fun preferencesAreIsolatedAndReplaceable() = runBlocking {
        val dao = database.preferencesDao()

        dao.insertPreferences(
            listOf(
                UserPreferenceEntity("user-1", "DEPORTES"),
                UserPreferenceEntity("user-1", "CULTURA"),
                UserPreferenceEntity("user-2", "ESTUDIO")
            )
        )

        dao.replaceUserPreferences(
            "user-1",
            listOf(
                UserPreferenceEntity("user-1", "ENTRETENIMIENTO")
            )
        )

        val user1 = dao.getUserPreferences("user-1").first()
        val user2 = dao.getUserPreferences("user-2").first()

        assertEquals(
            listOf("ENTRETENIMIENTO"),
            user1.map { it.category }
        )

        assertEquals(
            listOf("ESTUDIO"),
            user2.map { it.category }
        )
    }

    @Test
    fun pendingActionsAreStoredUpdatedAndDeleted() = runBlocking {
        val dao = database.pendingActionDao()

        val first = PendingActionEntity(
            id = "action-1",
            userId = "user-1",
            actionType = "CREATE_ACTIVITY",
            payload = """{"title":"Football"}""",
            createdAt = 1000L
        )

        val second = PendingActionEntity(
            id = "action-2",
            userId = "user-1",
            actionType = "JOIN_ACTIVITY",
            payload = """{"activityId":"activity-1"}""",
            createdAt = 2000L
        )

        dao.insertAction(second)
        dao.insertAction(first)

        val pending = dao.getPendingActions("user-1")

        assertEquals(2, pending.size)
        assertEquals("action-1", pending[0].id)
        assertEquals("action-2", pending[1].id)

        dao.updateAction(first.copy(retryCount = 1))

        val updated = dao.getPendingActions("user-1")
        assertEquals(1, updated[0].retryCount)

        dao.deleteAction("action-1", "user-1")

        val remaining = dao.getPendingActions("user-1")
        assertEquals(1, remaining.size)
        assertEquals("action-2", remaining[0].id)

        dao.clearUserActions("user-1")

        assertTrue(dao.getPendingActions("user-1").isEmpty())
    }

    @Test
    fun queueActivityCreationStoresPendingActionInDatabase() = runBlocking {
        val pendingActionDao = database.pendingActionDao()
        val repository = PendingActionRepository(pendingActionDao)
        val testUserId = "user-test-123"
        val otherUserId = "user-test-456"

        val request = CreateActivityRequest(
            title = "Board Games Night",
            category = "JUEGO",
            startTime = "2026-11-15T18:00:00Z",
            endTime = "2026-11-15T21:00:00Z",
            locationName = "Student Center",
            latitude = 4.601,
            longitude = -74.065,
            description = "Fun night playing board games",
            price = "Gratis",
            capacity = 8
        )

        val actionId = repository.queueActivityCreation(testUserId, request)

        val pendingActions = pendingActionDao.getPendingActions(testUserId)

        assertEquals(1, pendingActions.size)
        val savedAction = pendingActions.first()

        assertEquals(actionId, savedAction.id)
        assertEquals(testUserId, savedAction.userId)
        assertEquals("CREATE_ACTIVITY", savedAction.actionType)

        val gson = Gson()
        val deserializedRequest = gson.fromJson(savedAction.payload, CreateActivityRequest::class.java)
        assertEquals(request, deserializedRequest)

        val otherUserActions = pendingActionDao.getPendingActions(otherUserId)
        assertTrue(otherUserActions.isEmpty())
    }

    @Test
    fun syncPendingActions_whenBackendReturns201_deletesActionAndReturnsSyncedCount() = runBlocking {
        val pendingActionDao = database.pendingActionDao()
        val mockApiService = mock(ApiService::class.java)

        val testUserId = "user-sync-201"
        val request = CreateActivityRequest(
            title = "Soccer Game",
            category = "DEPORTES",
            startTime = "2026-11-20T10:00:00Z",
            endTime = "2026-11-20T12:00:00Z",
            locationName = "Campus Field",
            latitude = 4.601,
            longitude = -74.065
        )

        // Mock backend returning HTTP 201 Created after declaring request
        val successResponse = Response.success(201, JsonObject())
        `when`(mockApiService.createActivity(request)).thenReturn(successResponse)

        val repository = PendingActionRepository(
            pendingActionDao = pendingActionDao,
            apiServiceProvider = { mockApiService }
        )

        repository.queueActivityCreation(testUserId, request)

        // Execute synchronization
        val syncedCount = repository.syncPendingActions(testUserId)

        // Assertions: 1 action synchronized, action deleted from Room
        assertEquals(1, syncedCount)
        val remainingActions = pendingActionDao.getPendingActions(testUserId)
        assertTrue(remainingActions.isEmpty())
    }

    @Test
    fun syncPendingActions_whenBackendReturns500_retainsActionIncrementsRetryCountAndReturnsZero() = runBlocking {
        val pendingActionDao = database.pendingActionDao()
        val mockApiService = mock(ApiService::class.java)

        val testUserId = "user-sync-500"
        val request = CreateActivityRequest(
            title = "Music Jam",
            category = "MÚSICA",
            startTime = "2026-11-21T18:00:00Z",
            endTime = "2026-11-21T20:00:00Z",
            locationName = "Music Room",
            latitude = 4.601,
            longitude = -74.065
        )

        // Mock backend returning HTTP 500 Internal Server Error after declaring request
        val errorResponse = Response.error<JsonObject>(500, "Internal Error".toResponseBody(null))
        `when`(mockApiService.createActivity(request)).thenReturn(errorResponse)

        val repository = PendingActionRepository(
            pendingActionDao = pendingActionDao,
            apiServiceProvider = { mockApiService }
        )

        repository.queueActivityCreation(testUserId, request)

        // Execute synchronization
        val syncedCount = repository.syncPendingActions(testUserId)

        // Assertions: 0 actions synchronized, action retained in Room with retryCount = 1
        assertEquals(0, syncedCount)
        val remainingActions = pendingActionDao.getPendingActions(testUserId)
        assertEquals(1, remainingActions.size)

        val retainedAction = remainingActions.first()
        assertEquals(1, retainedAction.retryCount)
    }
}