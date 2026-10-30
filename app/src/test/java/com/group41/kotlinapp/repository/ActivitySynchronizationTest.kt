package com.group41.kotlinapp.repository

import com.google.gson.JsonParser
import com.group41.kotlinapp.cache.ActivityMemoryCache
import com.group41.kotlinapp.data.local.dao.ActivityDao
import com.group41.kotlinapp.data.local.entity.ActivityEntity
import com.group41.kotlinapp.data.local.toEntity
import com.group41.kotlinapp.model.Activity
import com.group41.kotlinapp.network.ApiService
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ActivitySynchronizationTest {
    private val original = Activity("a", "Activity", "Deportes", 60, 0, participants = 3)
    private class Disk : ActivityDao {
        val rows = MutableStateFlow(emptyList<ActivityEntity>())
        override fun getAllActivities() = rows
        override suspend fun getActivityById(id: String) = rows.value.firstOrNull { it.id == id }
        override suspend fun insertActivities(activities: List<ActivityEntity>) {
            rows.value = (rows.value.associateBy { it.id } + activities.associateBy { it.id }).values.toList()
        }
        override suspend fun insertActivity(activity: ActivityEntity) = insertActivities(listOf(activity))
        override suspend fun clearAll() { rows.value = emptyList() }
    }
    private fun service(handler: (String) -> Any?): ApiService = Proxy.newProxyInstance(
        ApiService::class.java.classLoader, arrayOf(ApiService::class.java)
    ) { _, method, _ -> handler(method.name) } as ApiService
    private fun detail(joined: Boolean, count: Int) = Response.success(JsonParser.parseString(
        """{"id":"a","title":"Activity","category":"DEPORTES","joined":$joined,"participants":$count}"""
    ).asJsonObject)
    @After fun cleanup() = ActivityMemoryCache.clear()

    @Test fun onlineDetailReplacesStaleMemoryAndDisk() = runBlocking {
        val disk = Disk()
        disk.insertActivity(original.toEntity())
        ActivityMemoryCache.put(original)
        val repo = ActivityRepository(disk, apiProvider = { service { detail(true, 8) } }, online = { true })
        val updated = repo.getActivityById("a")!!
        assertTrue(updated.joined)
        assertEquals(8, updated.participants)
        assertEquals(updated.toEntity(), disk.getActivityById("a"))
        assertEquals(updated, ActivityMemoryCache.get("a"))
    }
    @Test fun offlineRestartRestoresMembershipAndCountFromDisk() = runBlocking {
        val disk = Disk()
        disk.insertActivity(original.copy(joined = true, pendingSync = true, participants = 4).toEntity())
        ActivityMemoryCache.clear()
        val repo = ActivityRepository(disk, apiProvider = { service { throw IOException("offline") } }, online = { false })
        val restored = repo.getActivityById("a")!!
        assertTrue(restored.joined)
        assertTrue(restored.pendingSync)
        assertEquals(4, restored.participants)
    }
    @Test fun refreshPreservesPendingJoinWithoutRepeatedCountIncrements() = runBlocking {
        val disk = Disk()
        disk.insertActivity(original.copy(joined = true, pendingSync = true, participants = 4).toEntity())
        val repo = ActivityRepository(disk, apiProvider = { service { detail(false, 3) } }, online = { true })
        repeat(2) {
            val updated = repo.getActivityById("a")!!
            assertTrue(updated.pendingSync)
            assertEquals(4, updated.participants)
        }
    }
    @Test fun serverConfirmationClearsPendingAndUsesServerCount() = runBlocking {
        val disk = Disk()
        disk.insertActivity(original.copy(joined = true, pendingSync = true, participants = 4).toEntity())
        val repo = ActivityRepository(disk, apiProvider = { service { detail(true, 6) } }, online = { true })
        val updated = repo.getActivityById("a")!!
        assertTrue(updated.joined)
        assertFalse(updated.pendingSync)
        assertEquals(6, updated.participants)
    }
    @Test fun serverOnlyVerificationNeverAcceptsOfflineCache() = runBlocking {
        val disk = Disk()
        disk.insertActivity(original.copy(joined = true).toEntity())
        val repo = ActivityRepository(disk, apiProvider = { service { throw IOException("offline") } }, online = { true })
        assertNull(repo.getActivityById("a", serverOnly = true))
    }
    @Test fun successfulJoinRefreshesAuthoritativeCount() = runBlocking {
        val disk = Disk()
        disk.insertActivity(original.toEntity())
        val api = service { if (it == "join") JsonParser.parseString("{}").asJsonObject else detail(true, 9) }
        val repo = ActivityRepository(disk, apiProvider = { api }, online = { true })
        assertEquals(JoinResult.SYNCED, repo.joinActivity("a", null, null))
        assertEquals(9, disk.getActivityById("a")!!.participants)
        assertTrue(disk.getActivityById("a")!!.joined)
    }
    @Test fun conflictIsSuccessOnlyForConfirmedMembership() = runBlocking {
        for (joined in listOf(true, false)) {
            ActivityMemoryCache.clear()
            val disk = Disk()
            disk.insertActivity(original.toEntity())
            val api = service {
                if (it == "join") throw HttpException(Response.error<Any>(409, "conflict".toResponseBody()))
                detail(joined, 4)
            }
            val repo = ActivityRepository(disk, apiProvider = { api }, online = { true })
            try {
                assertEquals(JoinResult.SYNCED, repo.joinActivity("a", null, null))
                assertTrue(joined)
            } catch (e: HttpException) {
                assertFalse(joined)
                assertEquals(409, e.code())
            }
        }
    }
}
