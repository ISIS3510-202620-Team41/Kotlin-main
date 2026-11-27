package com.group41.kotlinapp.model

import com.group41.kotlinapp.network.FreeSlotDto
import com.group41.kotlinapp.network.UserDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleConcurrencyEngineTest {

    private val userMe = UserDto("u-me", "me@uniandes.edu.co", "Tomás", null, null)
    private val friend1 = UserDto("u-f1", "f1@uniandes.edu.co", "Martín", null, null)
    private val friend2 = UserDto("u-f2", "f2@uniandes.edu.co", "Sebastián", null, null)

    @Test
    fun testParseIsoToMinutes() {
        val iso = "2026-11-10T14:30:00-05:00"
        val minutes = TimeInterval.parseIsoToMinutes(iso)
        assertEquals(14 * 60 + 30, minutes)
    }

    @Test
    fun testSingleUserAvailability() = runBlocking {
        val mySlots = listOf(
            FreeSlotDto("2026-11-10T10:00:00-05:00", "2026-11-10T12:00:00-05:00")
        )

        val result = ScheduleConcurrencyEngine.computeMatrix(
            myUser = userMe,
            mySlots = mySlots,
            friendItems = emptyList()
        )

        assertEquals(1, result.fullOverlapGaps.size)
        assertEquals(120, result.totalCommonFreeMinutes)
        assertEquals(10 * 60, result.fullOverlapGaps[0].interval.startMinutes)
        assertEquals(12 * 60, result.fullOverlapGaps[0].interval.endMinutes)
    }

    @Test
    fun testTwoUsersOverlap() = runBlocking {
        // Yo libre de 10:00 a 14:00 (600 a 840)
        val mySlots = listOf(
            FreeSlotDto("2026-11-10T10:00:00-05:00", "2026-11-10T14:00:00-05:00")
        )
        // Amigo 1 libre de 12:00 a 16:00 (720 a 960)
        val friendItem = FriendAvailabilityItem(
            user = friend1,
            freeIntervals = listOf(TimeInterval(12 * 60, 16 * 60)),
            isSelected = true
        )

        val result = ScheduleConcurrencyEngine.computeMatrix(
            myUser = userMe,
            mySlots = mySlots,
            friendItems = listOf(friendItem)
        )

        // Solape esperado: de 12:00 a 14:00 (120 minutos)
        assertEquals(1, result.fullOverlapGaps.size)
        val gap = result.fullOverlapGaps[0]
        assertEquals(12 * 60, gap.interval.startMinutes)
        assertEquals(14 * 60, gap.interval.endMinutes)
        assertEquals(120, gap.durationMinutes)
        assertTrue(gap.isFullOverlap)
        assertEquals(2, gap.totalParticipants)
    }

    @Test
    fun testThreeUsersPartialAndFullOverlap() = runBlocking {
        // Yo: 12:00 a 15:00
        val mySlots = listOf(
            FreeSlotDto("2026-11-10T12:00:00-05:00", "2026-11-10T15:00:00-05:00")
        )
        // Amigo 1: 12:00 a 15:00
        val f1 = FriendAvailabilityItem(
            user = friend1,
            freeIntervals = listOf(TimeInterval(12 * 60, 15 * 60)),
            isSelected = true
        )
        // Amigo 2: 13:00 a 16:00
        val f2 = FriendAvailabilityItem(
            user = friend2,
            freeIntervals = listOf(TimeInterval(13 * 60, 16 * 60)),
            isSelected = true
        )

        val result = ScheduleConcurrencyEngine.computeMatrix(
            myUser = userMe,
            mySlots = mySlots,
            friendItems = listOf(f1, f2)
        )

        // Solape total: de 13:00 a 15:00 (120 minutos)
        assertEquals(1, result.fullOverlapGaps.size)
        val full = result.fullOverlapGaps[0]
        assertEquals(13 * 60, full.interval.startMinutes)
        assertEquals(15 * 60, full.interval.endMinutes)
        assertEquals(120, full.durationMinutes)
        assertTrue(full.isFullOverlap)
    }

    @Test
    fun testDisjointSchedulesProduceNoFullOverlap() = runBlocking {
        // Yo: 08:00 a 10:00
        val mySlots = listOf(
            FreeSlotDto("2026-11-10T08:00:00-05:00", "2026-11-10T10:00:00-05:00")
        )
        // Amigo 1: 14:00 a 16:00
        val f1 = FriendAvailabilityItem(
            user = friend1,
            freeIntervals = listOf(TimeInterval(14 * 60, 16 * 60)),
            isSelected = true
        )

        val result = ScheduleConcurrencyEngine.computeMatrix(
            myUser = userMe,
            mySlots = mySlots,
            friendItems = listOf(f1)
        )

        assertTrue(result.fullOverlapGaps.isEmpty())
        assertEquals(0, result.totalCommonFreeMinutes)
    }
}
