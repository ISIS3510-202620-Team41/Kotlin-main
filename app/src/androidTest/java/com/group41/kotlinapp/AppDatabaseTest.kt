package com.group41.kotlinapp.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group41.kotlinapp.data.local.entity.ActivityEntity
import com.group41.kotlinapp.data.local.entity.UserPreferenceEntity
import com.group41.kotlinapp.data.local.entity.PendingActionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
}
