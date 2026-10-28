package com.group41.kotlinapp.cache

import com.group41.kotlinapp.model.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ActivityMemoryCacheTest {

    @Before
    fun setUp() {
        ActivityMemoryCache.clear()
    }

    private fun sampleActivity(id: String, name: String = "Actividad $id"): Activity {
        return Activity(
            id = id,
            name = name,
            category = "Deportes",
            durationMinutes = 60,
            distanceMeters = 500,
            price = "Gratis",
            capacity = 20,
            participants = 5
        )
    }

    @Test
    fun testPutAndGet() {
        val act = sampleActivity("act-1", "Fútbol 5")
        ActivityMemoryCache.put(act)

        val retrieved = ActivityMemoryCache.get("act-1")
        assertNotNull(retrieved)
        assertEquals("Fútbol 5", retrieved?.name)
        assertEquals(1, ActivityMemoryCache.size())
    }

    @Test
    fun testLruEvictionWhenCapacityExceeded() {
        // Llenar la caché hasta el límite exacto (50)
        for (i in 1..ActivityMemoryCache.MAX_ENTRIES) {
            ActivityMemoryCache.put(sampleActivity("act-$i"))
        }
        assertEquals(50, ActivityMemoryCache.size())
        assertNotNull(ActivityMemoryCache.get("act-1"))

        // Al agregar el elemento 51, se desaloja el más antiguo
        ActivityMemoryCache.put(sampleActivity("act-51"))
        assertEquals(50, ActivityMemoryCache.size())
        assertNotNull(ActivityMemoryCache.get("act-51"))
    }

    @Test
    fun testAccessOrderAffectsEviction() {
        // Insertamos act-1 y luego act-2 hasta act-50
        ActivityMemoryCache.put(sampleActivity("act-1"))
        for (i in 2..50) {
            ActivityMemoryCache.put(sampleActivity("act-$i"))
        }

        // Accedemos a act-1 para que pase a ser el MÁS recientemente usado (MRU)
        val accessed = ActivityMemoryCache.get("act-1")
        assertNotNull(accessed)

        // Insertamos act-51: ahora el más antiguo no es act-1 sino act-2
        ActivityMemoryCache.put(sampleActivity("act-51"))

        // act-1 debe seguir vivo porque fue accedido recientemente
        assertNotNull(ActivityMemoryCache.get("act-1"))
        // act-2 debe haber sido desalojado
        assertNull(ActivityMemoryCache.get("act-2"))
        assertEquals(50, ActivityMemoryCache.size())
    }

    @Test
    fun testSnapshotAndClear() {
        ActivityMemoryCache.put(sampleActivity("a1"))
        ActivityMemoryCache.put(sampleActivity("a2"))

        val snapshot = ActivityMemoryCache.snapshot()
        assertEquals(2, snapshot.size)

        ActivityMemoryCache.clear()
        assertEquals(0, ActivityMemoryCache.size())
        assertEquals(0, ActivityMemoryCache.snapshot().size)
    }
}
