package com.group41.kotlinapp

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group41.kotlinapp.cache.ActivityMemoryCache
import com.group41.kotlinapp.data.local.AppDatabase
import com.group41.kotlinapp.data.local.toEntity
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.repository.ActivityRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityPersistenceTest {
    @Test fun joinedAndPendingSurviveDatabaseReopenOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "activity-persistence-${java.util.UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            for (pending in listOf(false, true)) {
                val activity = Activity("a", "Activity", "Deportes", 60, 0,
                    joined = true, participants = 4, pendingSync = pending)
                database.activityDao().insertActivity(activity.toEntity())
                database.close()
                ActivityMemoryCache.clear()
                database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
                val repo = ActivityRepository(database.activityDao(), online = { false },
                    apiProvider = { error("Offline reads must use Room") })
                val restored = repo.getActivityById("a")!!
                assertTrue(restored.joined)
                assertEquals(pending, restored.pendingSync)
                assertEquals(4, restored.participants)
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
            ActivityMemoryCache.clear()
        }
    }
}
